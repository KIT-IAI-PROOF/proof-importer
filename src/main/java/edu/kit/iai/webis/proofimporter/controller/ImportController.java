/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.proofimporter.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import edu.kit.iai.webis.proofimporter.ModelImporter;
import edu.kit.iai.webis.proofimporter.ModelImporter.ImportResult;
import edu.kit.iai.webis.proofutils.LoggingHelper;

/**
 * REST Controller for template and workflow imports
 * Provides HTTP endpoints to trigger imports via ModelImporter
 */
@RestController
@RequestMapping("/v1/import")
@CrossOrigin(origins = "*")
public class ImportController {

    @Autowired
    private ModelImporter modelImporter;

    /**
     * POST /v1/import/template - Import a template from a file path
     * 
     * Request parameters:
     * - templatePath (required): Path to the template JSON file
     * - attachmentsDir (optional): Directory containing attachment files
     * - save (optional, default: true): Whether to save to database
     * - overwrite (optional, default: false): Whether to overwrite existing entries
     * - checkReferences (optional, default: true): Whether to validate and prevent reference conflicts
     */
    @PostMapping(value = "/template", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportResponse> importTemplate(
            @RequestParam String templatePath,
            @RequestParam(required = false, defaultValue = "") String attachmentsDir,
            @RequestParam(required = false, defaultValue = "true") boolean save,
            @RequestParam(required = false, defaultValue = "false") boolean overwrite,
            @RequestParam(required = false, defaultValue = "true") boolean checkReferences) {

        LoggingHelper.info().log("REST: Importing template from " + templatePath);

        ImportResult result = modelImporter.importTemplate(
            templatePath,
            attachmentsDir.isEmpty() ? null : attachmentsDir,
            save,
            overwrite,
            checkReferences
        );

        ImportResponse response = new ImportResponse(
            result.returnCode.toString(),
            getResponseDescription(result.returnCode, "Template"),
            result.message,
            result.duration
        );

        return ResponseEntity.ok(response);
    }

    /**
     * POST /v1/import/workflow - Import a workflow from a file path
     * 
     * Request parameters:
     * - workflowPath (required): Path to the workflow JSON file
     * - attachmentsDir (optional): Directory containing attachment files
     * - save (optional, default: true): Whether to save to database
     * - overwrite (optional, default: false): Whether to overwrite existing entries
     * - checkReferences (optional, default: true): Whether to validate and prevent reference conflicts
     */
    @PostMapping(value = "/workflow", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportResponse> importWorkflow(
            @RequestParam String workflowPath,
            @RequestParam(required = false, defaultValue = "") String attachmentsDir,
            @RequestParam(required = false, defaultValue = "true") boolean save,
            @RequestParam(required = false, defaultValue = "false") boolean overwrite,
            @RequestParam(required = false, defaultValue = "true") boolean checkReferences) {

        LoggingHelper.info().log("REST: Importing workflow from " + workflowPath);

        ImportResult result = modelImporter.importWorkflow(
            workflowPath, 
            attachmentsDir.isEmpty() ? null : attachmentsDir,
            save, 
            overwrite,
            checkReferences
        );

        ImportResponse response = new ImportResponse(
            result.returnCode.toString(),
            getResponseDescription(result.returnCode, "Workflow"),
            result.message,
            result.duration
        );

        return ResponseEntity.ok(response);
    }

    /**
     * POST /v1/import/path - Import all templates and workflows from a directory
     * 
     * Request parameters:
     * - importPath (required): Directory containing template/workflow JSON files
     * - attachmentsDir (optional): Directory containing attachment files
     * - save (optional, default: true): Whether to save to database
     * - overwrite (optional, default: false): Whether to overwrite existing entries
     * - checkReferences (optional, default: true): Whether to validate and prevent reference conflicts
     */
    @PostMapping(value = "/path", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportResponse> importFromPath(
            @RequestParam String importPath,
            @RequestParam(required = false, defaultValue = "") String attachmentsDir,
            @RequestParam(required = false, defaultValue = "true") boolean save,
            @RequestParam(required = false, defaultValue = "false") boolean overwrite,
            @RequestParam(required = false, defaultValue = "true") boolean checkReferences) {

        LoggingHelper.info().log("REST: Importing from directory " + importPath);

        ImportResult result = modelImporter.importFromPath(
            importPath,
            attachmentsDir.isEmpty() ? null : attachmentsDir,
            save,
            overwrite,
            checkReferences
        );

        ImportResponse response = new ImportResponse(
            result.returnCode.toString(),
            getResponseDescription(result.returnCode, "Path"),
            result.message,
            result.duration
        );

        return ResponseEntity.ok(response);
    }

    /**
     * GET /v1/import/status - Get the status of the last import operation
     * Note: Now that all imports use ModelImporter directly, status is not tracked across operations.
     * Returns a simple health check response.
     */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportStatusResponse> getStatus() {
        LoggingHelper.debug().log("REST: Getting import status");

        ImportStatusResponse response = new ImportStatusResponse(
            "OK",
            "Import service is operational",
            0
        );

        return ResponseEntity.ok(response);
    }

    /**
     * GET /v1/import/health - Health check endpoint
     */
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HealthCheckResponse> healthCheck() {
        return ResponseEntity.ok(new HealthCheckResponse("UP", "Import service is running"));
    }

    /**
     * Response DTO for import operations
     */
    public static class ImportResponse {
        public String status;
        public String description;
        public String output;
        public long duration;

        public ImportResponse(String status, String description, String output, long duration) {
            this.status = status;
            this.description = description;
            this.output = output;
            this.duration = duration;
        }
    }

    /**
     * Response DTO for status endpoint
     */
    public static class ImportStatusResponse {
        public String status;
        public String message;
        public long duration;

        public ImportStatusResponse(String status, String message, long duration) {
            this.status = status;
            this.message = message;
            this.duration = duration;
        }
    }

    /**
     * Get description message based on import result return code
     * @param returnCode The return code from the import operation
     * @oaram objectType The object type as string 
     * @return A human-readable description of the result
     */
    private String getResponseDescription(ModelImporter.ReturnCode returnCode, String objectType) {
        return objectType + switch(returnCode) {
            case ERROR -> " import failed";
            case IMPORT_SUCCESS -> " import succeeded";
            case ID_EXISTS -> " ID already exists";
        };
    }

    /**
     * Response DTO for health check
     */
    public static class HealthCheckResponse {
        public String status;
        public String message;

        public HealthCheckResponse(String status, String message) {
            this.status = status;
            this.message = message;
        }
    }
}
