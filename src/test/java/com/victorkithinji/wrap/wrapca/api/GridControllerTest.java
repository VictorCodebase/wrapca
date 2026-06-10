package com.victorkithinji.wrap.wrapca.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.victorkithinji.wrap.wrapca.dto.SimulationModeEnum;
import com.victorkithinji.wrap.wrapca.dto.request.ModeOverrideRequestDto;
import com.victorkithinji.wrap.wrapca.dto.response.GridEnvironmentResponseDto;
import com.victorkithinji.wrap.wrapca.dto.response.SessionStatusResponseDto;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class GridControllerTest {

	@Mock
	private WrapSessionFacade facade;

	@InjectMocks
	private GridController controller;

	private MockMvc mockMvc;
	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	// -------------------------------------------------------------------------
	// /status
	// -------------------------------------------------------------------------

	@Test
	void getStatus_returns200WithSessionData() throws Exception {
		SessionStatusResponseDto dto = new SessionStatusResponseDto(
			SimulationModeEnum.PRE_FIRE,
			50, 50, 100.0,
			300000.0, 9800000.0, 305000.0, 9805000.0,
			Collections.emptyList());
		when(facade.getSessionStatus()).thenReturn(dto);

		mockMvc.perform(get("/api/session/status"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.mode").value("PRE_FIRE"))
			.andExpect(jsonPath("$.rows").value(50))
			.andExpect(jsonPath("$.cols").value(50))
			.andExpect(jsonPath("$.cellSizeMetres").value(100.0))
			.andExpect(jsonPath("$.pastRuns").isArray());
	}

	@Test
	void getStatus_returns503WhenGridNotInitialised() throws Exception {
		when(facade.getSessionStatus())
			.thenThrow(new IllegalStateException("Grid not initialised"));

		mockMvc.perform(get("/api/session/status"))
			.andExpect(status().isServiceUnavailable());
	}

	@Test
	void postRefresh_triggersRefreshAndReturnsStatus() throws Exception {
		SessionStatusResponseDto dto = new SessionStatusResponseDto(
			SimulationModeEnum.ACTIVE_FIRE,
			50, 50, 100.0,
			300000.0, 9800000.0, 305000.0, 9805000.0,
			Collections.emptyList());
		when(facade.getSessionStatus()).thenReturn(dto);

		mockMvc.perform(post("/api/session/refresh"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mode").value("ACTIVE_FIRE"));

		verify(facade).refreshSession();
	}

	@Test
	void postRefresh_returns503WhenGridStillNotReady() throws Exception {
		when(facade.getSessionStatus())
			.thenThrow(new IllegalStateException("Grid not initialised"));

		mockMvc.perform(post("/api/session/refresh"))
			.andExpect(status().isServiceUnavailable());
	}

	// -------------------------------------------------------------------------
	// /grid
	// -------------------------------------------------------------------------

	@Test
	void getGrid_returns200WithEnvironmentArrays() throws Exception {
		GridEnvironmentResponseDto dto = new GridEnvironmentResponseDto(
			new int[]{1, 2, 1, 2},
			new float[]{2500f, 2520f, 2510f, 2530f},
			new float[]{5.0f, 7.0f, 3.0f, 6.0f},
			2, 2, 100.0,
			300000.0, 9800000.0, 300200.0, 9800200.0);
		when(facade.getGridEnvironment()).thenReturn(dto);

		mockMvc.perform(get("/api/session/grid"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rows").value(2))
			.andExpect(jsonPath("$.cols").value(2))
			.andExpect(jsonPath("$.vegetationTypeOrdinals").isArray())
			.andExpect(jsonPath("$.elevationMetres").isArray())
			.andExpect(jsonPath("$.slopeDegrees").isArray())
			.andExpect(jsonPath("$.minX").value(300000.0));
	}

	@Test
	void getGrid_returns503WhenGridNotInitialised() throws Exception {
		when(facade.getGridEnvironment())
			.thenThrow(new IllegalStateException("Grid not initialised"));

		mockMvc.perform(get("/api/session/grid"))
			.andExpect(status().isServiceUnavailable());
	}

	// -------------------------------------------------------------------------
	// /mode
	// -------------------------------------------------------------------------

	@Test
	void postMode_validMode_returns200WithUpdatedStatus() throws Exception {
		SessionStatusResponseDto updated = new SessionStatusResponseDto(
			SimulationModeEnum.ACTIVE_FIRE,
			50, 50, 100.0,
			300000.0, 9800000.0, 305000.0, 9805000.0,
			Collections.emptyList());
		when(facade.setMode(SimulationModeEnum.ACTIVE_FIRE)).thenReturn(updated);

		ModeOverrideRequestDto req = new ModeOverrideRequestDto();
		req.setMode(SimulationModeEnum.ACTIVE_FIRE);

		mockMvc.perform(post("/api/session/mode")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(req)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mode").value("ACTIVE_FIRE"));

		verify(facade).setMode(SimulationModeEnum.ACTIVE_FIRE);
	}

	@Test
	void postMode_nullMode_returns400() throws Exception {
		mockMvc.perform(post("/api/session/mode")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"mode\": null}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void postMode_preFire_setsModeToPrFire() throws Exception {
		SessionStatusResponseDto updated = new SessionStatusResponseDto(
			SimulationModeEnum.PRE_FIRE,
			50, 50, 100.0,
			300000.0, 9800000.0, 305000.0, 9805000.0,
			Collections.emptyList());
		when(facade.setMode(SimulationModeEnum.PRE_FIRE)).thenReturn(updated);

		ModeOverrideRequestDto req = new ModeOverrideRequestDto();
		req.setMode(SimulationModeEnum.PRE_FIRE);

		mockMvc.perform(post("/api/session/mode")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(req)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mode").value("PRE_FIRE"));
	}
}