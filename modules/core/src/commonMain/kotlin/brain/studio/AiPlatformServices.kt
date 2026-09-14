package brain.studio

/**
 * Опциональный набор AI-сервисов конкретного platform shell.
 * StudioRepository может реализовать этот интерфейс, не расширяя основной Core-контракт.
 */
interface AiPlatformServices {
    val aiPackages: AiPackageGateway
    val cloudAi: CloudAiGateway
}
