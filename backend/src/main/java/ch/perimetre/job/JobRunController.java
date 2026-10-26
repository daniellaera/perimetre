package ch.perimetre.job;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Behind the ADMIN_TOKEN filter like everything under /admin. */
@RestController
class JobRunController {

    private final JobRunRepository runs;

    JobRunController(JobRunRepository runs) {
        this.runs = runs;
    }

    @GetMapping("/admin/job-runs")
    List<JobRun> latest(@RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return runs.latest(limit);
    }
}
