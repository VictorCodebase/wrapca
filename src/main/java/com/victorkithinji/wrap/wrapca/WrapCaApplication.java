package com.victorkithinji.wrap.wrapca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WrapCaApplication {

	public static void main(String[] args) {
		// Skip GeoTools PAM/GDAL metadata parsing — avoids JAXB NPE on Java 17+
		System.setProperty(
			"org.geotools.coverage.grid.io.AbstractGridCoverage2DReader.skipPAMDataset",
			"true");
		SpringApplication.run(WrapCaApplication.class, args);
	}

}
