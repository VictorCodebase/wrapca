package com.victorkithinji.wrap.wrapca.api;

import com.victorkithinji.wrap.wrapca.dto.SimulationModeEnum;
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

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

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
	void getStatus_propagatesExceptionFromFacade() throws Exception {
		when(facade.getSessionStatus())
			.thenThrow(new IllegalStateException("Grid not initialised"));

		// standaloneSetup does not install a global exception handler, so the
		// exception propagates — assert it is thrown rather than checking HTTP status
		org.junit.jupiter.api.Assertions.assertThrows(
			Exception.class,
			() -> mockMvc.perform(get("/api/session/status")));
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
}