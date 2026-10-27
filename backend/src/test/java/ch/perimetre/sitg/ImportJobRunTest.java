package ch.perimetre.sitg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

import ch.perimetre.job.HealthcheckPinger;
import ch.perimetre.job.JobRun.Trigger;

/**
 * ImportJob writes one job_run row per run, SUCCESS, FAILED or STALE_SOURCE, served by /admin/job-runs. SITG
 * and the healthchecks pinger are mocked; runs against the local DB like the other context tests, rolled back
 * afterwards.
 */
@SpringBootTest(properties = { "FIREBASE_CREDENTIALS=", "HEALTHCHECK_PING_URL=" })
@AutoConfigureMockMvc
@Transactional
class ImportJobRunTest {

    private static final LocalDate YESTERDAY = LocalDate.now(ZoneOffset.UTC).minusDays(1);

    @MockitoBean
    SitgImporter importer;

    @MockitoBean
    HealthcheckPinger pinger;

    @Autowired
    ImportJob job;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc mvc;

    @Test
    void successWritesARowWithTheCounters() {
        when(importer.run(ImportMode.FULL))
                .thenReturn(new SitgImporter.ImportResult(ImportMode.FULL, 120, 20, 100, List.of(), YESTERDAY, 7));

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
                .containsEntry("max_date_maj_sitg", Date.valueOf(YESTERDAY))
                .containsEntry("error_message", null);
        assertThat(row.get("finished_at")).isNotNull();
    }

    @Test
    void aFreshScheduledRunPingsSuccess() {
        when(importer.run(ImportMode.INCREMENTAL))
                .thenReturn(new SitgImporter.ImportResult(ImportMode.INCREMENTAL, 48, 9, 39, List.of(), YESTERDAY, 1));

        long id = job.run(ImportMode.INCREMENTAL, Trigger.SCHEDULED).jobRunId();

        assertThat(row(id)).containsEntry("status", "SUCCESS");
        verify(pinger).success(contains("SUCCESS"));
        verify(pinger, never()).fail(anyString());
    }

    @Test
    void aStaleSourceEndsStaleSourceAndPingsFail() {
        LocalDate fourDaysAgo = LocalDate.now(ZoneOffset.UTC).minusDays(4);
        when(importer.run(ImportMode.INCREMENTAL))
                .thenReturn(new SitgImporter.ImportResult(ImportMode.INCREMENTAL, 30, 0, 30, List.of(), fourDaysAgo, 1));

        long id = job.run(ImportMode.INCREMENTAL, Trigger.SCHEDULED).jobRunId();

        Map<String, Object> row = row(id);
        assertThat(row).containsEntry("status", "STALE_SOURCE")
                .containsEntry("fetched", 30)
                .containsEntry("max_date_maj_sitg", Date.valueOf(fourDaysAgo));
        assertThat((String) row.get("error_message")).isEqualTo(
                "stale source: newest date_maj_2 " + fourDaysAgo + " is older than 3 days");
        verify(pinger).fail(contains("STALE_SOURCE"));
        verify(pinger, never()).success(anyString());
    }

    @Test
    void anEmptyFetchIsAStaleSource() {
        when(importer.run(ImportMode.INCREMENTAL))
                .thenReturn(new SitgImporter.ImportResult(ImportMode.INCREMENTAL, 0, 0, 0, List.of(), null, 1));

        long id = job.run(ImportMode.INCREMENTAL, Trigger.MANUAL).jobRunId();

        assertThat(row(id)).containsEntry("status", "STALE_SOURCE").containsEntry("max_date_maj_sitg", null);
        verify(pinger, never()).fail(anyString()); // manual runs never ping
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
                .thenReturn(new SitgImporter.ImportResult(ImportMode.INCREMENTAL, 5, 1, 4, List.of(), YESTERDAY, 1));
        long first = job.run(ImportMode.INCREMENTAL, Trigger.MANUAL).jobRunId();
        long second = job.run(ImportMode.INCREMENTAL, Trigger.MANUAL).jobRunId();

        mvc.perform(get("/admin/job-runs").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[1].id").value(first))
                .andExpect(jsonPath("$[0].job_name").value("import-incremental"))
                .andExpect(jsonPath("$[0].fcm_errors").value(0))
                .andExpect(jsonPath("$[0].max_date_maj_sitg").value(YESTERDAY.toString()))
                .andExpect(jsonPath("$[0].duration_ms").isNumber());
        mvc.perform(get("/admin/job-runs").param("limit", "0")).andExpect(status().isBadRequest());
    }

    private Map<String, Object> row(long id) {
        return jdbc.sql("SELECT * FROM job_run WHERE id = ?").param(id).query().singleRow();
    }
}
