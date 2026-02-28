package com.mlplatform.controller;

import com.mlplatform.dto.PortalSectionResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/portal")
public class PortalController {

    @GetMapping("/sections")
    public List<PortalSectionResponse> sections() {
        return List.of(
                new PortalSectionResponse("dashboard", "Dashboard", "/dashboard", "home", true),
                new PortalSectionResponse("analyses", "Analyses", "/analyses", "folder", true),
                new PortalSectionResponse("models", "Models", "/models", "cpu", true),
                new PortalSectionResponse("pipelines", "Pipelines", "/pipelines", "workflow", true),
                new PortalSectionResponse("notebook-images", "Custom Images", "/notebook-images", "layers", true)
        );
    }
}
