package com.victorkithinji.wrap.wrapca.api;

import com.victorkithinji.wrap.wrapca.dto.SimulationModeEnum;
import com.victorkithinji.wrap.wrapca.facade.WrapSessionFacade;
import com.victorkithinji.wrap.wrapca.history.RunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class RunHistoryControllerTest {

	@Mock
	private WrapSessionFacade facade;

	@InjectMocks
	private RunHistoryController controller;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void getAllRuns_emptyList_returns200WithEmptyArray() throws Exception {
		when(facade.getAllRuns()).thenReturn(Collections.emptyList());

		mockMvc.perform(get("/api/runs"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$").isArray())
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void getAllRuns_returnsRecords() throws Exception {
		RunRecord r = new RunRecord(
			"run-abc",
			SimulationModeEnum.PRE_FIRE,
			Instant.parse("2025-06-01T06:00:00Z"),
			Instant.parse("2025-06-01T06:05:00Z"),
			Map.of("monteCarloRuns", 200),
			null);
		when(facade.getAllRuns()).thenReturn(List.of(r));

		mockMvc.perform(get("/api/runs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].runId").value("run-abc"))
			.andExpect(jsonPath("$[0].phase").value("PRE_FIRE"));
	}

	@Test
	void getRunById_existingId_returns200() throws Exception {
		RunRecord r = new RunRecord(
			"run-xyz",
			SimulationModeEnum.ACTIVE_FIRE,
			Instant.now(), Instant.now(),
			Map.of("simulationHours", 6),
			null);
		when(facade.getRunById("run-xyz")).thenReturn(r);

		mockMvc.perform(get("/api/runs/run-xyz"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runId").value("run-xyz"))
			.andExpect(jsonPath("$.phase").value("ACTIVE_FIRE"));
	}

	@Test
	void getRunById_missingId_returns404() throws Exception {
		when(facade.getRunById("not-found")).thenReturn(null);

		mockMvc.perform(get("/api/runs/not-found"))
			.andExpect(status().isNotFound());
	}
}