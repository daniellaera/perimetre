package ch.perimetre.sitg;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// /admin/** is guarded by the ADMIN_TOKEN bearer filter (ch.perimetre.admin).
@RestController
class ImportController {

    private final ImportJob job;

    ImportController(ImportJob job) {
        this.job = job;
    }

    /** {@code mode=incremental} (default) or {@code full}; 409 if an import is already running. */
    @PostMapping("/admin/import")
    ImportJob.RunResult runImport(@RequestParam(defaultValue = "incremental") String mode) {
        ImportMode parsed;
        try {
            parsed = ImportMode.parse(mode);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return job.run(parsed);
    }
}
