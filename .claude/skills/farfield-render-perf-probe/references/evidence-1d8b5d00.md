Cause : `JAVA_TOOL_OPTIONS` (pour le profileur) casse la détection du JDK 21 par Gradle. Je passe par `jcmd` sur les JVM déjà lancées.

You're right, it failed: both Gradle runs stopped after 13–14 s. Reading the cause.

Le serveur est prêt en 28 s, le client a démarré, et le profileur JFR tourne sur les deux.