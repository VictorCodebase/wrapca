package com.victorkithinji.wrap.wrapca.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.victorkithinji.wrap.wrapca.dto.request.CvCorrectionRequestDto;
import com.victorkithinji.wrap.wrapca.dto.request.PhaseOneRunRequestDto;
import com.victorkithinji.wrap.wrapca.dto.request.PhaseTwoRunRequestDto;
import com.victorkithinji.wrap.wrapca.dto.response.PhaseOneResultResponseDto;
import com.victorkithinji.wrap.wrapca.dto.response.PhaseTwoResultResponseDto;
import com.victorkithinji.wrap.wrapca.facade.WrapSessionFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SimulationControllerTest {

	@Mock
	private WrapSessionFacade facade;

	@InjectMocks
	private SimulationController controller;

	private MockMvc mockMvc;
	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	// -------------------------------------------------------------------------
	// Phase 1
	// -------------------------------------------------------------------------

	@Test
	void phaseOneRun_emptyBody_returns200() throws Exception {
		PhaseOneResultResponseDto dto = new PhaseOneResultResponseDto(
			"run-1",
			new float[]{0.1f, 0.2f},
			new float[]{0.3f, 0.4f},
			new int[]{1, 1},
			1, 2);
		when(facade.runPhaseOne(any())).thenReturn(dto);

		mockMvc.perform(post("/api/simulation/phase-one/run")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runId").value("run-1"))
			.andExpect(jsonPath("$.rows").value(1))
			.andExpect(jsonPath("$.cols").value(2));

		verify(facade).runPhaseOne(any());
	}

	@Test
	void phaseOneRun_noBody_returns200() throws Exception {
		PhaseOneResultResponseDto dto = new PhaseOneResultResponseDto(
			"run-x", new float[0], new float[0], new int[0], 0, 0);
		when(facade.runPhaseOne(any())).thenReturn(dto);

		mockMvc.perform(post("/api/simulation/phase-one/run"))
			.andExpect(status().isOk());
	}

	@Test
	void phaseOneRun_withWindOverrides_passesThrough() throws Exception {
		PhaseOneResultResponseDto dto = new PhaseOneResultResponseDto(
			"run-2", new float[0], new float[0], new int[0], 0, 0);
		when(facade.runPhaseOne(any())).thenReturn(dto);

		PhaseOneRunRequestDto req = new PhaseOneRunRequestDto();
		req.setWindSpeedMsOverride(8.5);
		req.setWindDirectionDegOverride(180.0);

		mockMvc.perform(post("/api/simulation/phase-one/run")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(req)))
			.andExpect(status().isOk());

		verify(facade).runPhaseOne(any());
	}

	// -------------------------------------------------------------------------
	// Phase 2
	// -------------------------------------------------------------------------

	@Test
	void phaseTwoRun_validRequest_returns200() throws Exception {
		PhaseTwoResultResponseDto dto = new PhaseTwoResultResponseDto(
			"run-3", Collections.emptyList());
		when(facade.runPhaseTwo(any())).thenReturn(dto);

		PhaseTwoRunRequestDto req = new PhaseTwoRunRequestDto();
		req.setSimulationHours(6);

		mockMvc.perform(post("/api/simulation/phase-two/run")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(req)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runId").value("run-3"))
			.andExpect(jsonPath("$.perimetersByTimestamp").isArray());
	}

	@Test
	void phaseTwoRun_manualIgnition_passesGeoJson() throws Exception {
		PhaseTwoResultResponseDto dto = new PhaseTwoResultResponseDto(
			"run-4", Collections.emptyList());
		when(facade.runPhaseTwo(any())).thenReturn(dto);

		PhaseTwoRunRequestDto req = new PhaseTwoRunRequestDto();
		req.setManualIgnition(true);
		req.setManualIgnitionPolygonGeoJson("{\"type\":\"Polygon\"}");
		req.setSimulationHours(2);

		mockMvc.perform(post("/api/simulation/phase-two/run")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(req)))
			.andExpect(status().isOk());
	}

	// -------------------------------------------------------------------------
	// CV correction
	// -------------------------------------------------------------------------

	@Test
	void applyCorrection_emptyBody_returns204() throws Exception {
		mockMvc.perform(post("/api/simulation/phase-two/correct")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isNoContent());

		verify(facade).applyCorrection(any());
	}

	@Test
	void applyCorrection_noBody_returns204() throws Exception {
		mockMvc.perform(post("/api/simulation/phase-two/correct"))
			.andExpect(status().isNoContent());
	}

	@Test
	void applyCorrection_withSuppressedIds_returns204() throws Exception {
		CvCorrectionRequestDto req = new CvCorrectionRequestDto();
		req.setSuppressedZoneCellIds(List.of("5", "10", "15"));

		mockMvc.perform(post("/api/simulation/phase-two/correct")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(req)))
			.andExpect(status().isNoContent());
	}
}