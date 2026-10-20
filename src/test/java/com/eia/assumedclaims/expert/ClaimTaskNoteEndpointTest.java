package com.eia.assumedclaims.expert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.eia.assumedclaims.expert.TaskNoteFixtures.CLAIM_ID;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.TASK_ID;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.TENANT;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.note;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.task;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ClaimTaskController.class, ClaimNoteController.class})
class ClaimTaskNoteEndpointTest {
    private static final String BASE = "/api/v1/assumed-claims/claims/" + CLAIM_ID;
    private static final String SHORT_BASE = "/api/v1/assumed-claims/" + CLAIM_ID;
    private static final String INTERNAL_BASE = "/internal/v1/assumed-claims/" + CLAIM_ID;
    private static final String TENANT_PARAM = TENANT.toString();
    private static final String ASSUMED = BookScopeFilter.ASSUMED_BOOK;
    private static final String DIRECT = "DIRECT";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ClaimTaskService taskService;
    @MockitoBean
    private ClaimNoteService noteService;

    // -------------------------------------------------------------------- tasks
    @Test
    void listsTasks() throws Exception {
        when(taskService.list(TENANT, CLAIM_ID))
                .thenReturn(List.of(task("OPEN", 1), task("COMPLETE", 2)));

        mvc.perform(get(BASE + "/tasks").param("tenantId", TENANT_PARAM)
                        .header("X-EIA-Book", ASSUMED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(TASK_ID.toString()))
                .andExpect(jsonPath("$[0].claimId").value(CLAIM_ID.toString()))
                .andExpect(jsonPath("$[0].taskNumber").value("TSK-2026-0000001"))
                .andExpect(jsonPath("$[0].title").value("Review medical records"))
                .andExpect(jsonPath("$[0].description").value("Order records and index the file"))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].priority").value("HIGH"))
                .andExpect(jsonPath("$[0].dueDate").value("2026-04-15"))
                .andExpect(jsonPath("$[0].assignedTo").value("adjuster@example.com"))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].updatedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].completedAt").value(nullValue()))
                .andExpect(jsonPath("$[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$[1].status").value("COMPLETE"));
    }

    @Test
    void listsTasksWithTheTenantHeader() throws Exception {
        when(taskService.list(TENANT, CLAIM_ID)).thenReturn(List.of(task("OPEN", 1)));

        mvc.perform(get(BASE + "/tasks").header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskNumber").value("TSK-2026-0000001"));
    }

    @Test
    void listsTasksThroughThePublicAlias() throws Exception {
        when(taskService.list(TENANT, CLAIM_ID)).thenReturn(List.of(task("OPEN", 1)));

        mvc.perform(get(SHORT_BASE + "/tasks").param("tenantId", TENANT_PARAM)
                        .header("X-EIA-Book", ASSUMED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void listsTasksThroughTheInternalAlias() throws Exception {
        when(taskService.list(TENANT, CLAIM_ID)).thenReturn(List.of(task("OPEN", 1)));

        mvc.perform(get(INTERNAL_BASE + "/tasks").header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void createsATask() throws Exception {
        when(taskService.create(eq(TENANT), eq(CLAIM_ID), any(CreateTaskRequest.class), isNull()))
                .thenReturn(new CommandResult<>(task("OPEN", 1), false));

        mvc.perform(post(BASE + "/tasks")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Review medical records",
                                  "description": "Order records and index the file",
                                  "dueDate": "2026-04-15",
                                  "priority": "HIGH",
                                  "assignedTo": "adjuster@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.taskNumber").value("TSK-2026-0000001"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1));

        verify(taskService).create(eq(TENANT), eq(CLAIM_ID), any(CreateTaskRequest.class), isNull());
    }

    @Test
    void createsATaskWithAnIdempotencyKey() throws Exception {
        when(taskService.create(eq(TENANT), eq(CLAIM_ID), any(CreateTaskRequest.class),
                eq("task-key-1"))).thenReturn(new CommandResult<>(task("OPEN", 1), false));

        mvc.perform(post(BASE + "/tasks")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .header("Idempotency-Key", "task-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Review medical records",
                                  "dueDate": "2026-04-15",
                                  "priority": "HIGH"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.taskNumber").value("TSK-2026-0000001"));

        verify(taskService).create(eq(TENANT), eq(CLAIM_ID), any(CreateTaskRequest.class),
                eq("task-key-1"));
    }

    @Test
    void replaysATaskCreateWithTheOriginalProjection() throws Exception {
        when(taskService.create(eq(TENANT), eq(CLAIM_ID), any(CreateTaskRequest.class),
                eq("task-key-1"))).thenReturn(new CommandResult<>(task("OPEN", 1), true));

        mvc.perform(post(BASE + "/tasks")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .header("Idempotency-Key", "task-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Review medical records",
                                  "dueDate": "2026-04-15",
                                  "priority": "HIGH"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.taskNumber").value("TSK-2026-0000001"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void completesATask() throws Exception {
        when(taskService.changeStatus(eq(TENANT), eq(CLAIM_ID), eq(TASK_ID),
                any(TaskStatusChangeRequest.class))).thenReturn(task("COMPLETE", 2));

        mvc.perform(patch(BASE + "/tasks/" + TASK_ID)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETE\", \"version\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
    }

    @Test
    void taskRoutesStayInsideTheAssumedBookWall() throws Exception {
        mvc.perform(get(BASE + "/tasks").param("tenantId", TENANT_PARAM)
                        .header("X-EIA-Book", DIRECT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7005"));
        mvc.perform(get(SHORT_BASE + "/tasks").param("tenantId", TENANT_PARAM)
                        .header("X-EIA-Book", DIRECT))
                .andExpect(status().isNotFound());
        mvc.perform(get(INTERNAL_BASE + "/tasks").header("X-EIA-Tenant", TENANT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7005"));
        mvc.perform(post(BASE + "/tasks")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", DIRECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Review medical records",
                                  "dueDate": "2026-04-15",
                                  "priority": "HIGH"
                                }
                                """))
                .andExpect(status().isNotFound());
        mvc.perform(patch(BASE + "/tasks/" + TASK_ID)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", DIRECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETE\", \"version\": 1}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(taskService);
    }

    @Test
    void taskValidationRejectsMissingTitleAndDueDate() throws Exception {
        mvc.perform(post(BASE + "/tasks")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\": \"HIGH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7004"))
                .andExpect(jsonPath("$.errors[*].field", hasItems("title", "dueDate")));

        verifyNoInteractions(taskService);
    }

    @Test
    void taskPatchRequiresAVersion() throws Exception {
        mvc.perform(patch(BASE + "/tasks/" + TASK_ID)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("version")));

        verifyNoInteractions(taskService);
    }

    @Test
    void staleTaskVersionIs409() throws Exception {
        when(taskService.changeStatus(eq(TENANT), eq(CLAIM_ID), eq(TASK_ID),
                any(TaskStatusChangeRequest.class)))
                .thenThrow(ApiException.taskVersionConflict(2));

        mvc.perform(patch(BASE + "/tasks/" + TASK_ID)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETE\", \"version\": 2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7802"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void missingTaskIs404() throws Exception {
        when(taskService.changeStatus(eq(TENANT), eq(CLAIM_ID), eq(TASK_ID),
                any(TaskStatusChangeRequest.class)))
                .thenThrow(ApiException.taskNotFound(TASK_ID));

        mvc.perform(patch(BASE + "/tasks/" + TASK_ID)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETE\", \"version\": 1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7801"));
    }

    // -------------------------------------------------------------------- notes
    @Test
    void listsNotes() throws Exception {
        when(noteService.list(TENANT, CLAIM_ID))
                .thenReturn(List.of(note("SETTLEMENT", "Second"), note("DIARY", "First")));

        mvc.perform(get(BASE + "/notes").param("tenantId", TENANT_PARAM)
                        .header("X-EIA-Book", ASSUMED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(note("SETTLEMENT", "Second").id().toString()))
                .andExpect(jsonPath("$[0].claimId").value(CLAIM_ID.toString()))
                .andExpect(jsonPath("$[0].noteType").value("SETTLEMENT"))
                .andExpect(jsonPath("$[0].noteNumber").value("NTE-2026-0000001"))
                .andExpect(jsonPath("$[0].body").value("Second"))
                .andExpect(jsonPath("$[0].author").value("adjuster@example.com"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$[1].noteType").value("DIARY"));
    }

    @Test
    void listsNotesThroughThePublicAndInternalAliases() throws Exception {
        when(noteService.list(TENANT, CLAIM_ID)).thenReturn(List.of(note("GENERAL", "First")));

        mvc.perform(get(SHORT_BASE + "/notes").param("tenantId", TENANT_PARAM)
                        .header("X-EIA-Book", ASSUMED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].noteType").value("GENERAL"));

        mvc.perform(get(INTERNAL_BASE + "/notes").header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].noteType").value("GENERAL"));
    }

    @Test
    void createsANote() throws Exception {
        when(noteService.add(eq(TENANT), eq(CLAIM_ID), any(CreateNoteRequest.class), isNull()))
                .thenReturn(new CommandResult<>(note("DIARY", "Called the claimant"), false));

        mvc.perform(post(BASE + "/notes")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "noteType": "DIARY",
                                  "body": "Called the claimant",
                                  "author": "adjuster@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.noteNumber").value("NTE-2026-0000001"))
                .andExpect(jsonPath("$.noteType").value("DIARY"))
                .andExpect(jsonPath("$.author").value("adjuster@example.com"));

        verify(noteService).add(eq(TENANT), eq(CLAIM_ID), any(CreateNoteRequest.class), isNull());
    }

    @Test
    void replaysANoteAppendWithTheOriginalProjection() throws Exception {
        when(noteService.add(eq(TENANT), eq(CLAIM_ID), any(CreateNoteRequest.class),
                eq("note-key-1")))
                .thenReturn(new CommandResult<>(note("DIARY", "Called the claimant"), true));

        mvc.perform(post(BASE + "/notes")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .header("Idempotency-Key", "note-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "noteType": "DIARY",
                                  "body": "Called the claimant",
                                  "author": "adjuster@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.noteNumber").value("NTE-2026-0000001"));
    }

    @Test
    void noteRoutesStayInsideTheAssumedBookWall() throws Exception {
        mvc.perform(get(BASE + "/notes").param("tenantId", TENANT_PARAM)
                        .header("X-EIA-Book", DIRECT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7005"));
        mvc.perform(get(INTERNAL_BASE + "/notes").header("X-EIA-Tenant", TENANT))
                .andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/notes")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", DIRECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "noteType": "DIARY",
                                  "body": "Called the claimant",
                                  "author": "adjuster@example.com"
                                }
                                """))
                .andExpect(status().isNotFound());
        verifyNoInteractions(noteService);
    }

    @Test
    void noteValidationRejectsAnEmptyBody() throws Exception {
        mvc.perform(post(BASE + "/notes")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"noteType\": \"DIARY\", \"author\": \"adjuster@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("body")));

        verifyNoInteractions(noteService);
    }

    @Test
    void noteReadRequiresATenant() throws Exception {
        mvc.perform(get(BASE + "/notes").header("X-EIA-Book", ASSUMED))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7004"));

        verifyNoInteractions(noteService);
    }
}
