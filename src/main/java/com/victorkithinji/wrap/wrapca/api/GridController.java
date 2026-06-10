package com.victorkithinji.wrap.wrapca.api;

import com.victorkithinji.wrap.wrapca.dto.request.ModeOverrideRequestDto;
import com.victorkithinji.wrap.wrapca.dto.response.GridEnvironmentResponseDto;
import com.victorkithinji.wrap.wrapca.dto.response.SessionStatusResponseDto;
import com.victorkithinji.wrap.wrapca.facade.WrapSessionFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP layer for session management endpoints.
 * No logic lives here — all delegation goes to WrapSessionFacade.
 */
@Slf4j
@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
public class GridController {

	private final WrapSessionFacade facade;

	/**
	 * GET /api/session/status
	 * Returns 200 with session data when the grid is ready.
	 * Returns 503 with a plain message when the grid has not yet loaded.
	 */
	@GetMapping("/status")
	public ResponseEntity<?> status() {
		try {
			return ResponseEntity.ok(facade.getSessionStatus());
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(e.getMessage());
		}
	}

	/**
	 * POST /api/session/refresh
	 * Triggers an immediate full session reload. Returns 200 with status
	 * when the grid loaded successfully, or 503 when data is still unavailable.
	 */
	@PostMapping("/refresh")
	public ResponseEntity<?> refresh() {
		log.info("Manual session refresh requested via API");
		facade.refreshSession();
		try {
			return ResponseEntity.ok(facade.getSessionStatus());
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(e.getMessage());
		}
	}

	/**
	 * GET /api/session/grid
	 * Returns static per-cell environmental data (vegetation type, elevation,
	 * slope) for the frontend map render. Available immediately after startup
	 * once the grid has loaded — does not require any simulation run.
	 * Returns 503 when the grid is not yet initialised.
	 */
	@GetMapping("/grid")
	public ResponseEntity<?> gridEnvironment() {
		try {
			return ResponseEntity.ok(facade.getGridEnvironment());
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body("{\"error\": \"Grid not initialised\"}");
		}
	}

	/**
	 * POST /api/session/mode
	 * Manually overrides the session mode (PRE_FIRE or ACTIVE_FIRE).
	 * Does not alter grid state or simulation history.
	 * The 3-hour CV poll may subsequently override this back to ACTIVE_FIRE
	 * if a fire perimeter is detected — CV observation takes precedence.
	 * Returns 400 when mode field is missing or null.
	 */
	@PostMapping("/mode")
	public ResponseEntity<SessionStatusResponseDto> setMode(
		@Valid @RequestBody ModeOverrideRequestDto request) {
		return ResponseEntity.ok(facade.setMode(request.getMode()));
	}
}