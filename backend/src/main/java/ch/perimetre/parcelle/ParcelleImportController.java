package ch.perimetre.parcelle;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ch.perimetre.job.JobRun.Trigger;
import ch.perimetre.sitg.ImportMode;

// /admin/** is guarded by the ADMIN_TOKEN bearer filter (ch.perimetre.admin).
@RestController
class ParcelleImportController {

    private final ParcelleJob job;

    ParcelleImportController(ParcelleJob job) {
        this.job = job;
    }

    /**
     * {@code mode=incremental} (default) or {@code full}; the very first run is full whatever the mode.
     * 409 if a parcel import is already running.
     */
    @PostMapping("/admin/import/parcelles")
    ParcelleJob.RunResult runImport(@RequestParam(defaultValue = "incremental") String mode) {
        ImportMode parsed;
        try {
            parsed = ImportMode.parse(mode);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return job.run(parsed, Trigger.MANUAL);
    }
}
