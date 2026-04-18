package com.victorkithinji.wrap.wrapca.api;

import com.victorkithinji.wrap.wrapca.dto.request.CvCorrectionRequestDto;
import com.victorkithinji.wrap.wrapca.dto.request.PhaseOneRunRequestDto;
import com.victorkithinji.wrap.wrapca.dto.request.PhaseTwoRunRequestDto;
import com.victorkithinji.wrap.wrapca.dto.response.PhaseOneResultResponseDto;
import com.victorkithinji.wrap.wrapca.dto.response.PhaseTwoResultResponseDto;
import com.victorkithinji.wrap.wrapca.facade.WrapSessionFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP layer for simulation run endpoints.
 * No logic lives here — all delegation goes to WrapSessionFacade.
 */
@Slf4j
@RestController
@RequestMapping("/api/simulation")
@RequiredArgsConstructor
public class SimulationController {

	private final WrapSessionFacade facade;

	/**
	 * POST /api/simulation/phase-one/run
	 * Runs the Phase 1 Monte Carlo ensemble. Returns dual-layer risk maps
	 * (damage potential + ignition probability) and vegetation type ordinals.
	 * Request body may be empty — all fields are optional overrides.
	 */
	@PostMapping("/phase-one/run")
	public ResponseEntity<PhaseOneResultResponseDto> runPhaseOne(
		@RequestBody(required = false) PhaseOneRunRequestDto request) {
		PhaseOneRunRequestDto effective = request != null ? request : new PhaseOneRunRequestDto();
		return ResponseEntity.ok(facade.runPhaseOne(effective));
	}

	/**
	 * POST /api/simulation/phase-two/run
	 * Runs a Phase 2 active-fire spread simulation. Returns perimeter snapshots
	 * per generation. simulationHours is required; other fields are optional.
	 */
	@PostMapping("/phase-two/run")
	public ResponseEntity<PhaseTwoResultResponseDto> runPhaseTwo(
		@Valid @RequestBody PhaseTwoRunRequestDto request) {
		return ResponseEntity.ok(facade.runPhaseTwo(request));
	}

	/**
	 * POST /api/simulation/phase-two/correct
	 * Applies a CV overpass correction to the live Phase 2 grid.
	 * Must be called after phase-two/run has been invoked at least once.
	 * All request fields are optional — an empty body is a no-op correction.
	 */
	@PostMapping("/phase-two/correct")
	public ResponseEntity<Void> applyCorrection(
		@RequestBody(required = false) CvCorrectionRequestDto request) {
		CvCorrectionRequestDto effective = request != null
			? request
			: new CvCorrectionRequestDto();
		facade.applyCorrection(effective);
		return ResponseEntity.noContent().build();
	}
}