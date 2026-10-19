import java.nio.file.Files

/**
 * Dev run dirs start from the templates in dev/ (ADR-042). A file already in
 * the run dir is never overwritten, so local changes and the game's own
 * writes survive.
 */
class DevTemplates {

    /** Copies the files of {@code from} missing in {@code into}; returns the files created. */
    static List<File> copyMissing(File from, File into) {
        List<File> created = []
        from.eachFileRecurse { File source ->
            if (!source.file) {
                return
            }
            File target = new File(into, from.toPath().relativize(source.toPath()).toString())
            if (!target.exists()) {
                target.parentFile.mkdirs()
                Files.copy(source.toPath(), target.toPath())
                created << target
            }
        }
        return created
    }
}
