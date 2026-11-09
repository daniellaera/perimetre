package ch.perimetre.parcelle;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ch.perimetre.job.JobRun.Trigger;
import ch.perimetre.sitg.ImportMode;

// /admin/** is guarded by the ADMIN_TOKEN bearer filter (ch.perimetre.admin).
@RestController
class ContexteImportController {

    private final ContexteJob job;

    ContexteImportController(ContexteJob job) {
        this.job = job;
    }

    /**
     * Runs one layer ({@code source}: batiments, adresses, zones, lieux-dits) or all of them (default), in that
     * order. {@code mode=incremental} (default) or {@code full}; only the buildings have an incremental mode,
     * and the first run of each layer is full. 409 if one is already running, 400 for an unknown source or mode.
     */
    @PostMapping("/admin/import/contexte")
    List<ContexteJob.RunResult> runImport(@RequestParam(defaultValue = "all") String source,
            @RequestParam(defaultValue = "incremental") String mode) {
        try {
            return job.run(job.select(source), ImportMode.parse(mode), Trigger.MANUAL);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
