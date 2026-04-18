package com.victorkithinji.wrap.wrapca.api;

import com.victorkithinji.wrap.wrapca.dto.response.SessionStatusResponseDto;
import com.victorkithinji.wrap.wrapca.facade.WrapSessionFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
	 * Returns the current session mode, grid dimensions, bounding box, and run history summary.
	 */
	@GetMapping("/status")
	public ResponseEntity<SessionStatusResponseDto> status() {
		return ResponseEntity.ok(facade.getSessionStatus());
	}

	/**
	 * POST /api/session/refresh
	 * Triggers an immediate full session reload: re-fetches CV GeoTIFF,
	 * rebuilds the grid, and re-polls for fire mode. Same pipeline as the
	 * scheduled 3-hour refresh. Returns the updated status.
	 */
	@PostMapping("/refresh")
	public ResponseEntity<SessionStatusResponseDto> refresh() {
		log.info("Manual session refresh requested via API");
		facade.refreshSession();
		return ResponseEntity.ok(facade.getSessionStatus());
	}
}