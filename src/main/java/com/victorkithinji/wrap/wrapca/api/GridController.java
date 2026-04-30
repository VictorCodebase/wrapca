package com.victorkithinji.wrap.wrapca.api;

import com.victorkithinji.wrap.wrapca.dto.response.SessionStatusResponseDto;
import com.victorkithinji.wrap.wrapca.facade.WrapSessionFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
}