package ch.perimetre.sitg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import ch.perimetre.job.JobRun.Trigger;

/**
 * ImportJob writes one job_run row per run, SUCCESS or FAILED, served by /admin/job-runs. SITG is mocked;
 * runs against the local DB like the other context tests, rolled back afterwards.
 */
@SpringBootTest(properties = { "FIREBASE_CREDENTIALS=", "HEALTHCHECK_PING_URL=" })
@AutoConfigureMockMvc
@Transactional
class ImportJobRunTest {

    @MockitoBean
    SitgImporter importer;

    @Autowired
    ImportJob job;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc mvc;

    @Test
    void successWritesARowWithTheCounters() {
        when(importer.run(ImportMode.FULL))
                .thenReturn(new SitgImporter.ImportResult(ImportMode.FULL, 120, 20, 100, List.of(), 7));

        ImportJob.RunResult result = job.run(ImportMode.FULL, Trigger.MANUAL);

        Map<String, Object> row = row(result.jobRunId());
        assertThat(row).containsEntry("job_name", "import-full")
                .containsEntry("trigger", "MANUAL")
                .containsEntry("status", "SUCCESS")
                .containsEntry("fetched", 120)
                .containsEntry("changed", 0)
                .containsEntry("notified", 0)
                .containsEntry("pushes", 0)
                .containsEntry("fcm_errors", 0)
                .containsEntry("error_message", null);
        assertThat(row.get("finished_at")).isNotNull();
    }

    @Test
    void failureWritesAFailedRow() {
        when(importer.run(ImportMode.INCREMENTAL)).thenThrow(new IllegalStateException("SITG down"));

        assertThatThrownBy(() -> job.run(ImportMode.INCREMENTAL, Trigger.SCHEDULED)).hasMessage("SITG down");

        Map<String, Object> row = row(jdbc.sql("SELECT max(id) FROM job_run").query(Long.class).single());
        assertThat(row).containsEntry("job_name", "import-incremental")
                .containsEntry("trigger", "SCHEDULED")
                .containsEntry("status", "FAILED")
                .containsEntry("fetched", null)
                .containsEntry("changed", null);
        assertThat((String) row.get("error_message")).contains("SITG down");
        assertThat(row.get("finished_at")).isNotNull();
    }

    @Test
    void jobRunsNewestFirst() throws Exception {
        when(importer.run(ImportMode.INCREMENTAL))
                .thenReturn(new SitgImporter.ImportResult(ImportMode.INCREMENTAL, 5, 1, 4, List.of(), 1));
        long first = job.run(ImportMode.INCREMENTAL, Trigger.MANUAL).jobRunId();
        long second = job.run(ImportMode.INCREMENTAL, Trigger.MANUAL).jobRunId();

        mvc.perform(get("/admin/job-runs").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[1].id").value(first))
                .andExpect(jsonPath("$[0].job_name").value("import-incremental"))
                .andExpect(jsonPath("$[0].fcm_errors").value(0))
                .andExpect(jsonPath("$[0].duration_ms").isNumber());
        mvc.perform(get("/admin/job-runs").param("limit", "0")).andExpect(status().isBadRequest());
    }

    private Map<String, Object> row(long id) {
        return jdbc.sql("SELECT * FROM job_run WHERE id = ?").param(id).query().singleRow();
    }
}
