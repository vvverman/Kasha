// Статическая/растровая проверка, затем опционально browser QA. Без сетевых запросов.
import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
const require = createRequire(import.meta.url);
const root = path.dirname(fileURLToPath(import.meta.url));
const out = path.join(root, 'qa');
await fs.mkdir(out, { recursive: true });
function dependency(name) {
 try { return require(name); }
 catch { return require(path.join(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES || '', name)); }
}
const staticChecks = [];
const staticAssert = (condition, name) => { if (!condition) throw new Error(name); staticChecks.push(name); };
const html = await fs.readFile(path.join(root, 'atlas.html'), 'utf8');
const source = JSON.parse(await fs.readFile(path.join(root, 'registry.json'), 'utf8'));
const scripts = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)];
new Function(scripts.at(-1)[1]);
staticChecks.push('Синтаксис JavaScript каталога проверен Node');
const embedded = JSON.parse(scripts[0][1]);
staticAssert(embedded.icons.length === source.icons.length, 'Embedded JSON содержит все '+source.icons.length+' glyphs');
staticAssert(source.currentGlyphs.every(alias => embedded.aliasMap[alias]), 'Все 22 прежних Glyph имеют alias');
staticAssert(embedded.aliasMap.MAGIC === 'text-processing', 'MAGIC направлен на text-processing');
staticAssert(!/(?:src|href)=["']https?:/.test(html), 'У HTML нет внешних src/href и CDN');
staticAssert(source.icons.every(i => i.motion.every(m => m.durationMs <= 320)), 'Анимации ограничены однократными 220–300 мс');
const markup = html.split('<script')[0];
const ids = [...markup.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]);
staticAssert(ids.length === new Set(ids).size, 'ID статической HTML-разметки уникальны');
staticAssert(markup.includes('for="query"') && markup.includes('aria-label="Категории иконок"'), 'Поле поиска и группа категорий подписаны');
const sharp = dependency('sharp');
for (const mode of ['dark', 'light']) await sharp(path.join(root, 'contact-' + mode + '.svg')).png().toFile(path.join(out, 'contact-' + mode + '.png'));
let rasterCount = 0;
for (const icon of source.icons) {
 for (const size of [20,24,32]) {
  const {data,info} = await sharp(path.join(root,'svg',icon.id+'.svg')).resize(size,size).ensureAlpha().raw().toBuffer({resolveWithObject:true});
  let visible=0,edge=0;
  for(let y=0;y<info.height;y++)for(let x=0;x<info.width;x++){
   const alpha=data[(y*info.width+x)*info.channels+info.channels-1];
   if(alpha>8){visible++;if(x===0||y===0||x===info.width-1||y===info.height-1)edge++;}
  }
  staticAssert(visible>0&&edge===0, icon.id+': '+size+'px видим, без обрезки краями viewport');
  rasterCount++;
 }
}
const baseline = {kind:'Проверка инженерного каталога, не production UI',staticChecks,rasterCount,contactSheets:['contact-dark.png','contact-light.png'],browser:{status:'not-run',reason:'Статический режим; браузерные сценарии не проверялись'}};
if(process.argv.includes('--static')){
 await fs.writeFile(path.join(out,'report.json'),JSON.stringify(baseline,null,2)+'\n');
 console.log(JSON.stringify({staticChecks:staticChecks.length,rasterCount,browser:baseline.browser},null,2));
 process.exit(0);
}
let browser;
try { browser = await dependency('playwright').chromium.launch({ headless: true }); }
catch(error){
 baseline.browser={status:'blocked',reason:String(error).split('\n').slice(0,3).join(' ')};
 await fs.writeFile(path.join(out,'report.json'),JSON.stringify(baseline,null,2)+'\n');
 console.error('Статика и '+rasterCount+' растров проверены. Browser QA заблокирован: '+baseline.browser.reason);
 process.exit(2);
}
const context = await browser.newContext({ viewport: { width: 1440, height: 1100 }, deviceScaleFactor: 1, offline: true });
const page = await context.newPage();
const errors = [], failed = [], checks = [];
page.on('pageerror', e => errors.push(String(e)));
page.on('requestfailed', r => failed.push({ url: r.url(), error: r.failure()?.errorText }));
const assert = (condition, name) => { if (!condition) throw new Error(name); checks.push(name); };
await page.goto(pathToFileURL(path.join(root, 'atlas.html')).href);
await page.evaluate(() => document.fonts.ready);
assert(await page.locator('.glyph-card').count() === source.icons.length, 'Все glyphs видимы при открытии file://');
assert(await page.locator('.brand svg path').count() === 3, 'Показан настоящий трёхчастный знак');
await page.screenshot({ path: path.join(out, 'atlas-dark-desktop.png'), fullPage: true });
await page.locator('.glyph-card[data-id="mic"]').focus();
await page.keyboard.press('Enter');
assert(await page.locator('#detail-title').textContent() === 'Микрофон', 'Enter выбирает glyph');
assert(await page.locator('.glyph-card[data-id="mic"]').getAttribute('aria-pressed') === 'true', 'Выбор доступен через aria-pressed');
await page.locator('.glyph-card[data-id="complete"]').focus();
await page.keyboard.press('Space');
assert(await page.locator('#detail-title').textContent() === 'Выполнить задачу', 'Пробел выбирает completion');
assert(await page.locator('#variants svg').count() === 2, 'Показаны unchecked / filled варианты completion');
const sizes = await page.locator('#detail-sizes svg').evaluateAll(nodes => nodes.map(n => Number(n.getAttribute('width'))));
assert(JSON.stringify(sizes) === '[20,24,32]', 'Геометрия отображается в 20/24/32');
await page.locator('#query').fill('MAGIC');
assert(await page.locator('.glyph-card').count() === 1 && await page.locator('.glyph-card').getAttribute('data-id') === 'text-processing', 'Legacy MAGIC ведёт на text-processing');
await page.locator('#query').fill('несуществующая-иконка');
assert(await page.locator('.empty').count() === 1, 'Пустой результат объяснён');
await page.locator('#query').fill('');
await page.locator('[data-category="navigation"]').click();
assert(await page.locator('.glyph-card').count() === 4, 'Категории фильтруют glyphs');
await page.locator('[data-category="all"]').click();
await page.locator('#motion').click();
await page.locator('#replay').click();
assert(await page.evaluate(() => document.getAnimations().filter(a => a.playState === 'running').length) === 0, 'Ручной Reduce Motion отключает анимации');
await page.locator('#motion').click();
await page.emulateMedia({ reducedMotion: 'reduce' });
await page.locator('#replay').click();
assert(await page.evaluate(() => document.getAnimations().filter(a => a.playState === 'running').length) === 0, 'Системный Reduce Motion отключает анимации');
await page.emulateMedia({ reducedMotion: 'no-preference' });
await page.locator('#theme').click();
assert(await page.locator('html').getAttribute('data-theme') === 'light', 'Переключение светлой темы');
await page.screenshot({ path: path.join(out, 'atlas-light-desktop.png'), fullPage: true });
await page.setViewportSize({ width: 390, height: 844 });
await page.locator('#theme').click();
await page.screenshot({ path: path.join(out, 'atlas-dark-mobile.png'), fullPage: true });
assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'На ширине 390 нет горизонтального переполнения');
await page.setViewportSize({ width: 320, height: 740 });
assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'На ширине 320 нет горизонтального переполнения');
const targetSizes = await page.locator('button,input').evaluateAll(nodes => nodes.filter(n => n.getClientRects().length).map(n => ({ id: n.id || n.textContent.trim(), h: n.getBoundingClientRect().height, w: n.getBoundingClientRect().width })));
assert(targetSizes.every(r => r.h >= 44 && r.w >= 44), 'Все кнопки и поле имеют target минимум 44 × 44');
assert(errors.length === 0, 'Нет JavaScript ошибок');
const report = { ...baseline, browser:{status:'passed',engine:'Chromium / Playwright'}, offline: true, checks, errors, failedRequests: failed, screenshots: ['atlas-dark-desktop.png', 'atlas-light-desktop.png', 'atlas-dark-mobile.png'] };
await fs.writeFile(path.join(out, 'report.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
await browser.close();
