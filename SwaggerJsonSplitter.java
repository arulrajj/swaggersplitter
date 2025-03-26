import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SwaggerSplitter {

    private static final Logger LOGGER = Logger.getLogger(SwaggerSplitter.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
    private static final int MAX_FILENAME_LENGTH = 100;

    public static void main(String[] args) {
        configureLogger();
        
        if (args.length < 1) {
            LOGGER.severe("Usage: java SwaggerSplitter <input-swagger-file> [output-directory]");
            System.exit(1);
        }

        String inputFile = args[0];
        String outputDir = args.length > 1 ? args[1] : "output";

        try {
            validateInputFile(inputFile);
            splitSwaggerFile(inputFile, outputDir);
            LOGGER.info("Successfully split Swagger file into individual API files in: " + outputDir);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing Swagger file: " + e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void configureLogger() {
        System.setProperty("java.util.logging.SimpleFormatter.format", 
            "[%1$tF %1$tT] [%4$-7s] %5$s %n");
    }

    private static void validateInputFile(String inputFile) throws IOException {
        if (!Files.exists(Paths.get(inputFile))) {
            throw new IOException("Input file does not exist: " + inputFile);
        }
        if (!inputFile.toLowerCase().endsWith(".json")) {
            throw new IOException("Input file must be a JSON file");
        }
    }

    public static void splitSwaggerFile(String inputFile, String outputDir) throws IOException {
        JsonNode rootNode = readAndValidateSwaggerJson(inputFile);
        Path outputPath = createOutputDirectory(outputDir);

        JsonNode infoNode = rootNode.path("info");
        JsonNode serversNode = rootNode.path("servers");
        JsonNode componentsNode = rootNode.path("components");

        // Build complete schema registry and component registry
        Map<String, JsonNode> schemaRegistry = new HashMap<>();
        ObjectNode componentsRegistry = MAPPER.createObjectNode();
        if (!componentsNode.isMissingNode()) {
            componentsNode.fields().forEachRemaining(componentEntry -> {
                componentsRegistry.set(componentEntry.getKey(), componentEntry.getValue());
                if (componentEntry.getKey().equals("schemas")) {
                    componentEntry.getValue().fields().forEachRemaining(schemaEntry -> {
                        schemaRegistry.put(schemaEntry.getKey(), schemaEntry.getValue());
                    });
                }
            });
        }

        processApiPaths(rootNode.path("paths"), infoNode, serversNode, componentsRegistry, schemaRegistry, outputPath);
    }

    private static JsonNode readAndValidateSwaggerJson(String inputFile) throws IOException {
        try {
            JsonNode rootNode = MAPPER.readTree(new File(inputFile));
            if (!rootNode.has("paths") || !rootNode.get("paths").isObject()) {
                throw new IOException("Invalid Swagger/OpenAPI format: missing or invalid 'paths' section");
            }
            if (!rootNode.has("openapi") && !rootNode.has("swagger")) {
                throw new IOException("Invalid Swagger/OpenAPI format: missing 'openapi' or 'swagger' field");
            }
            return rootNode;
        } catch (IOException e) {
            throw new IOException("Failed to parse input JSON file: " + e.getMessage(), e);
        }
    }

    private static Path createOutputDirectory(String outputDir) throws IOException {
        Path outputPath = Paths.get(outputDir).toAbsolutePath();
        try {
            if (!Files.exists(outputPath)) {
                Files.createDirectories(outputPath);
                LOGGER.info("Created output directory: " + outputPath);
            }
            // Verify write permissions
            Path testFile = Files.createTempFile(outputPath, "test", ".tmp");
            Files.delete(testFile);
            return outputPath;
        } catch (IOException e) {
            throw new IOException("Failed to create output directory: " + outputPath + " - " + e.getMessage(), e);
        }
    }

    private static void processApiPaths(JsonNode pathsNode, JsonNode infoNode, JsonNode serversNode,
                                     ObjectNode componentsRegistry, Map<String, JsonNode> schemaRegistry,
                                     Path outputPath) {
        Iterator<Map.Entry<String, JsonNode>> pathEntries = pathsNode.fields();
        int apiCount = 0;
        int errorCount = 0;

        while (pathEntries.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathEntries.next();
            String path = pathEntry.getKey();
            JsonNode pathNode = pathEntry.getValue();

            try {
                // Find all referenced schemas (including nested dependencies)
                Set<String> requiredSchemas = findRequiredSchemas(pathNode, schemaRegistry);

                // Create API document with proper components
                ObjectNode apiDocument = createApiDocument(infoNode, serversNode, componentsRegistry, requiredSchemas, path, pathNode);

                // Write to file
                writeApiDocument(path, apiDocument, outputPath);
                apiCount++;
            } catch (Exception e) {
                errorCount++;
                LOGGER.log(Level.WARNING, "Failed to process API path: " + path + " - " + e.getMessage(), e);
            }
        }

        LOGGER.info(String.format("Processing complete. Success: %d, Failures: %d", apiCount, errorCount));
    }

    private static Set<String> findRequiredSchemas(JsonNode pathNode, Map<String, JsonNode> schemaRegistry) {
        Set<String> requiredSchemas = new HashSet<>();
        pathNode.fields().forEachRemaining(methodEntry -> {
            JsonNode operationNode = methodEntry.getValue();
            scanNodeForSchemaReferences(operationNode, requiredSchemas, schemaRegistry);
        });
        return requiredSchemas;
    }

    private static void scanNodeForSchemaReferences(JsonNode node, Set<String> requiredSchemas, Map<String, JsonNode> schemaRegistry) {
        if (node.isObject()) {
            // Check for direct schema reference
            if (node.has("$ref")) {
                handleSchemaReference(node.get("$ref").asText(), requiredSchemas, schemaRegistry);
            }

            // Check all standard fields that might contain schema references
            String[] schemaFields = {"schema", "items", "additionalProperties"};
            for (String field : schemaFields) {
                if (node.has(field)) {
                    scanNodeForSchemaReferences(node.get(field), requiredSchemas, schemaRegistry);
                }
            }

            // Check schema composition
            String[] compositionFields = {"allOf", "anyOf", "oneOf"};
            for (String field : compositionFields) {
                if (node.has(field)) {
                    node.get(field).forEach(item -> 
                        scanNodeForSchemaReferences(item, requiredSchemas, schemaRegistry));
                }
            }

            // Check properties
            if (node.has("properties")) {
                node.get("properties").fields().forEachRemaining(prop -> 
                    scanNodeForSchemaReferences(prop.getValue(), requiredSchemas, schemaRegistry));
            }

            // Check request/response content
            if (node.has("content")) {
                node.get("content").fields().forEachRemaining(content -> 
                    scanNodeForSchemaReferences(content.getValue(), requiredSchemas, schemaRegistry));
            }

            // Check parameters
            if (node.has("parameters")) {
                node.get("parameters").forEach(param -> 
                    scanNodeForSchemaReferences(param, requiredSchemas, schemaRegistry));
            }
        } else if (node.isArray()) {
            node.forEach(element -> 
                scanNodeForSchemaReferences(element, requiredSchemas, schemaRegistry));
        }
    }

    private static void handleSchemaReference(String ref, Set<String> requiredSchemas, Map<String, JsonNode> schemaRegistry) {
        if (ref.startsWith(SCHEMA_REF_PREFIX)) {
            String schemaName = ref.substring(SCHEMA_REF_PREFIX.length());
            if (!requiredSchemas.contains(schemaName) && schemaRegistry.containsKey(schemaName)) {
                requiredSchemas.add(schemaName);
                // Recursively scan the referenced schema for nested references
                scanNodeForSchemaReferences(schemaRegistry.get(schemaName), requiredSchemas, schemaRegistry);
            }
        }
    }

    private static ObjectNode createApiDocument(JsonNode infoNode, JsonNode serversNode, 
                                              ObjectNode componentsRegistry, Set<String> requiredSchemas,
                                              String path, JsonNode pathNode) {
        ObjectNode apiDocument = MAPPER.createObjectNode();

        // Add basic info
        if (!infoNode.isMissingNode()) apiDocument.set("info", infoNode);
        if (!serversNode.isMissingNode()) apiDocument.set("servers", serversNode);

        // Add filtered components with all required schemas
        ObjectNode filteredComponents = createFilteredComponents(componentsRegistry, requiredSchemas);
        if (filteredComponents.size() > 0) {
            apiDocument.set("components", filteredComponents);
        }

        // Add the single API path
        ObjectNode singlePathNode = MAPPER.createObjectNode();
        singlePathNode.set(path, pathNode);
        apiDocument.set("paths", singlePathNode);

        return apiDocument;
    }

    private static ObjectNode createFilteredComponents(ObjectNode componentsRegistry, Set<String> requiredSchemas) {
        ObjectNode filteredComponents = MAPPER.createObjectNode();
        
        // Copy all non-schema components as-is
        componentsRegistry.fields().forEachRemaining(entry -> {
            if (!entry.getKey().equals("schemas")) {
                filteredComponents.set(entry.getKey(), entry.getValue());
            }
        });

        // Add filtered schemas if any are required
        if (!requiredSchemas.isEmpty() && componentsRegistry.has("schemas")) {
            ObjectNode schemasNode = (ObjectNode) componentsRegistry.get("schemas");
            ObjectNode filteredSchemas = MAPPER.createObjectNode();
            
            requiredSchemas.forEach(schemaName -> {
                if (schemasNode.has(schemaName)) {
                    filteredSchemas.set(schemaName, schemasNode.get(schemaName));
                }
            });
            
            filteredComponents.set("schemas", filteredSchemas);
        }
        
        return filteredComponents;
    }

    private static void writeApiDocument(String path, ObjectNode apiDocument, Path outputPath) throws IOException {
        String filename = generateFilename(path);
        Path outputFile = outputPath.resolve(filename);
        
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), apiDocument);
            LOGGER.fine("Created API file: " + outputFile);
        } catch (IOException e) {
            throw new IOException("Failed to write API file: " + outputFile + " - " + e.getMessage(), e);
        }
    }

    private static String generateFilename(String path) {
        // Generate safe filename from path
        String filename = path.replaceAll("[^a-zA-Z0-9-]", "_")
                            .replaceAll("_+", "_")
                            .replaceAll("^_|_$", "");
        
        if (filename.isEmpty()) {
            filename = "unnamed_api";
        }
        
        // Truncate if too long while preserving extension
        if (filename.length() > MAX_FILENAME_LENGTH - 5) {
            filename = filename.substring(0, MAX_FILENAME_LENGTH - 5);
        }
        
        return filename + ".json";
    }
}
