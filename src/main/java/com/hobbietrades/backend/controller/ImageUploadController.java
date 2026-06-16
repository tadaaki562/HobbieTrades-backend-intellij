package com.hobbietrades.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hobbietrades.backend.model.Item;
import com.hobbietrades.backend.model.ItemGalleryImage;
import com.hobbietrades.backend.repository.ItemGalleryImageRepository;
import com.hobbietrades.backend.repository.ItemRepository;
import com.hobbietrades.backend.service.BrandModelResolver;
import com.hobbietrades.backend.service.ItemPhotoValidationService;
import com.hobbietrades.backend.service.ItemPhotoValidationService.ValidationResult;
import com.hobbietrades.backend.service.ItemValidationException;
import com.hobbietrades.backend.service.RoboflowVisionService;
import com.hobbietrades.backend.util.HobbyCategories;
import com.hobbietrades.backend.util.UploadValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/items")
public class ImageUploadController {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private RoboflowVisionService roboflowVisionService;

    @Autowired
    private ItemPhotoValidationService photoValidationService;

    @Autowired
    private ItemGalleryImageRepository galleryRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${huggingface.api.key}")
    private String hfApiKey;

    @Value("${upload.dir:uploads/}")
    private String uploadDir;

    // ── Category keyword mapping (ViT ImageNet labels → HobbieTrades categories) ──
    private static final Map<String, String> LABEL_TO_CATEGORY = new LinkedHashMap<>();
    static {
        // Instruments
        for (String k : new String[]{"guitar","banjo","violin","cello","piano","drum",
                "saxophone","flute","trumpet","keyboard","harmonica","accordion",
                "maracas","ukulele","sitar","lute","oboe","trombone","tuba"}) {
            LABEL_TO_CATEGORY.put(k, "Instruments");
        }
        // Cameras
        for (String k : new String[]{"camera","reflex camera","polaroid","lens",
                "tripod","binoculars","telescope","microscope"}) {
            LABEL_TO_CATEGORY.put(k, "Cameras");
        }
    }

    private static final Map<String, String> LABEL_NORMALIZATION = new LinkedHashMap<>();
    static {
        LABEL_NORMALIZATION.put("acoustic guitar", "guitar");
        LABEL_NORMALIZATION.put("electric guitar", "guitar");
        LABEL_NORMALIZATION.put("bass guitar", "guitar");
        LABEL_NORMALIZATION.put("digital camera", "camera");
        LABEL_NORMALIZATION.put("camera lens", "lens");
        LABEL_NORMALIZATION.put("video game console", "console");
        LABEL_NORMALIZATION.put("game controller", "controller");
        LABEL_NORMALIZATION.put("table tennis", "tennis");
        LABEL_NORMALIZATION.put("paint brush", "paintbrush");
        LABEL_NORMALIZATION.put("sewing kit", "sewing machine");
        LABEL_NORMALIZATION.put("stereo", "speaker");
        LABEL_NORMALIZATION.put("loudspeaker", "speaker");
        LABEL_NORMALIZATION.put("head phones", "headphones");
        LABEL_NORMALIZATION.put("cell phone", "phone");
        LABEL_NORMALIZATION.put("smart phone", "phone");
        LABEL_NORMALIZATION.put("bike", "bicycle");
        LABEL_NORMALIZATION.put("football helmet", "football");
        LABEL_NORMALIZATION.put("soccer", "soccer ball");
        LABEL_NORMALIZATION.put("water colour", "watercolor");
    }

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9\\s]");
    private static final Set<String> DIRECT_PRIORITY_KEYWORDS = Set.of(
            "guitar", "piano", "violin", "drum", "camera", "lens", "console", "controller",
            "basketball", "football", "tennis", "paintbrush", "canvas", "sewing", "crochet"
    );

    // ── PH baseline second-hand values ₱ [Worn, Fair, Good, Like New] ──
    private static final Map<String, int[]> PH_VALUE_RANGES = new HashMap<>();
    static {
        PH_VALUE_RANGES.put("Instruments", new int[]{500,  1500,  5000, 18000});
        PH_VALUE_RANGES.put("Cameras",     new int[]{800,  2500,  7000, 20000});
    }

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/items/analyze  — AI analysis only, no DB write
    // Frontend calls this immediately when user selects a photo
    // ════════════════════════════════════════════════════════════════════════
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyzeOnly(
            @RequestParam("photo") MultipartFile photo) {

        Map<String, Object> result = new HashMap<>();
        try {
            UploadValidator.validateImage(photo);
            ValidationResult validation = photoValidationService.validate(
                    photo.getBytes(), this::runHuggingFaceOnly);
            Map<String, String> ai = validation.analysis();

            result.put("success",           true);
            result.put("accepted",          true);
            result.put("detectedCategory",  ai.get("category"));
            result.put("detectedCondition", ai.get("condition"));
            result.put("rawLabels",         ai.get("rawLabels"));
            result.put("caption",           ai.get("caption"));
            result.put("confidence",        ai.get("confidence"));
            result.put("suggestedTitle",    ai.get("suggestedTitle"));
            result.put("detectedBrand",     ai.get("detectedBrand"));
            result.put("detectedModel",     ai.get("detectedModel"));
            result.put("estimateKeyword",   ai.get("estimateKeyword"));
            result.put("detectionSource",   ai.getOrDefault("detectionSource", "huggingface"));
            result.put("message",           "Photo accepted — " + ai.get("category") + " detected.");
            return ResponseEntity.ok(result);

        } catch (ItemValidationException e) {
            result.put("success", false);
            result.put("accepted", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("accepted", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("accepted", false);
            result.put("message", "Analysis failed: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /** Validates a hobby proof photo (must show a camera or instrument). */
    @PostMapping("/validate-photo")
    public ResponseEntity<Map<String, Object>> validatePhoto(
            @RequestParam("photo") MultipartFile photo,
            @RequestParam(value = "slot", required = false) Integer slot) {

        Map<String, Object> result = new HashMap<>();
        try {
            UploadValidator.validateImage(photo);
            ValidationResult validation = photoValidationService.validate(
                    photo.getBytes(), this::runHuggingFaceOnly);
            result.put("success", true);
            result.put("accepted", true);
            result.put("slot", slot);
            result.put("detectedCategory", validation.category());
            result.put("confidence", validation.confidence());
            result.put("message", "Hobby photo accepted — " + validation.category() + " detected.");
            return ResponseEntity.ok(result);
        } catch (ItemValidationException | IllegalArgumentException e) {
            result.put("success", false);
            result.put("accepted", false);
            result.put("slot", slot);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("accepted", false);
            result.put("message", "Validation failed: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/items/{id}/upload  — save photo + update item in DB
    // Frontend calls this after item is saved, to attach the photo
    // ════════════════════════════════════════════════════════════════════════
    @PostMapping("/{id}/upload")
    public ResponseEntity<Map<String, Object>> uploadPhoto(
            @PathVariable Long id,
            @RequestParam("photo") MultipartFile photo) {

        Map<String, Object> response = new HashMap<>();

        Optional<Item> itemOpt = itemRepository.findById(id);
        if (itemOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "Item not found: " + id);
            return ResponseEntity.status(404).body(response);
        }
        Item item = itemOpt.get();

        byte[] imageBytes;
        try {
            UploadValidator.validateImage(photo);
            imageBytes = photo.getBytes();
            photoValidationService.validate(imageBytes, this::runHuggingFaceOnly);
        } catch (ItemValidationException | IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "Could not read photo: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }

        String photoUrl = "/api/items/" + id + "/photo";
        String mime = photo.getContentType() != null ? photo.getContentType() : "image/jpeg";

        try {
            saveFile(photo, id);
        } catch (IOException e) {
            System.out.println("[Upload] disk backup failed (non-fatal): " + e.getMessage());
        }

        item.setPhotoData(imageBytes);
        item.setPhotoMime(mime);
        item.setPhotoUrl(photoUrl);

        try {
            Map<String, String> ai = runAI(imageBytes);
            boolean catUpdated  = false;
            boolean condUpdated = false;

            if (isBlank(item.getCategory()) && !isBlank(ai.get("category"))) {
                item.setCategory(ai.get("category"));
                catUpdated = true;
            }
            if (isBlank(item.getConditionLabel()) && !isBlank(ai.get("condition"))) {
                item.setConditionLabel(ai.get("condition"));
                condUpdated = true;
            }

            itemRepository.save(item);

            response.put("success",           true);
            response.put("photoUrl",          photoUrl);
            response.put("detectedCategory",  ai.get("category"));
            response.put("detectedCondition", ai.get("condition"));
            response.put("rawLabels",         ai.get("rawLabels"));
            response.put("caption",           ai.get("caption"));
            response.put("suggestedTitle",    ai.get("suggestedTitle"));
            response.put("detectedBrand",     ai.get("detectedBrand"));
            response.put("detectedModel",     ai.get("detectedModel"));
            response.put("estimateKeyword",   ai.get("estimateKeyword"));
            response.put("categoryUpdated",   catUpdated);
            response.put("conditionUpdated",  condUpdated);
            response.put("message",           "Photo uploaded and analyzed successfully");

        } catch (Exception e) {
            // Photo saved even if AI failed — still return success
            itemRepository.save(item);
            response.put("success",  true);
            response.put("photoUrl", photoUrl);
            response.put("message",  "Photo saved (AI analysis failed: " + e.getMessage() + ")");
        }

        return ResponseEntity.ok(response);
    }

    // ════════════════════════════════════════════════════════════════════════
    // POST /api/items/{id}/upload-gallery — hobby authentication photos (slots 2–5)
    // ════════════════════════════════════════════════════════════════════════
    @PostMapping("/{id}/upload-gallery")
    public ResponseEntity<Map<String, Object>> uploadGallery(
            @PathVariable Long id,
            @RequestParam("photos") MultipartFile[] photos) {

        Map<String, Object> response = new HashMap<>();
        Optional<Item> itemOpt = itemRepository.findById(id);
        if (itemOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "Item not found: " + id);
            return ResponseEntity.status(404).body(response);
        }
        if (photos == null || photos.length != 5) {
            response.put("success", false);
            response.put("message", "Upload exactly 5 hobby proof photos (you using your hobby).");
            return ResponseEntity.badRequest().body(response);
        }

        Item item = itemOpt.get();
        galleryRepository.deleteByItemId(id);

        List<String> urls = new ArrayList<>();
        try {
            for (int i = 0; i < photos.length; i++) {
                MultipartFile file = photos[i];
                if (file == null || file.isEmpty()) {
                    response.put("success", false);
                    response.put("message", "Hobby photo " + (i + 1) + " is missing.");
                    return ResponseEntity.badRequest().body(response);
                }
                UploadValidator.validateImage(file);
                byte[] bytes = file.getBytes();
                photoValidationService.validate(bytes, this::runHuggingFaceOnly);

                int slot = i + 1;
                ItemGalleryImage galleryImage = new ItemGalleryImage();
                galleryImage.setItemId(id);
                galleryImage.setSlot(slot);
                galleryImage.setImageData(bytes);
                galleryImage.setMimeType(file.getContentType() != null ? file.getContentType() : "image/jpeg");
                galleryRepository.save(galleryImage);

                urls.add("/api/items/" + id + "/gallery/" + slot);
            }
            item.setGalleryUrls(String.join("|", urls));
            itemRepository.save(item);
            response.put("success", true);
            response.put("galleryUrls", urls);
            response.put("message", "Hobby authentication photos saved.");
            return ResponseEntity.ok(response);
        } catch (ItemValidationException e) {
            galleryRepository.deleteByItemId(id);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (IllegalArgumentException e) {
            galleryRepository.deleteByItemId(id);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "Gallery save failed: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Hugging Face only — used when Roboflow unavailable (strict category mapping)
    // ════════════════════════════════════════════════════════════════════════
    Map<String, String> runHuggingFaceOnly(byte[] imageBytes) throws Exception {
        Map<String, String> result = new HashMap<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        List<LabelScore> scores = callVitWithRetry(client, imageBytes,
                "https://router.huggingface.co/hf-inference/models/google/vit-base-patch16-224",
                "ViT-base");

        if (scores.isEmpty() || scores.get(0).score < 0.50) {
            List<LabelScore> largeScores = callVitWithRetry(client, imageBytes,
                    "https://router.huggingface.co/hf-inference/models/google/vit-large-patch16-224",
                    "ViT-large");
            if (!largeScores.isEmpty() &&
                    (scores.isEmpty() || largeScores.get(0).score > scores.get(0).score)) {
                scores = largeScores;
            }
        }

        if (scores.isEmpty()) {
            return null;
        }

        CategoryPick categoryPick = pickCategoryFromLabels(scores, true);
        if (!HobbyCategories.isAllowed(categoryPick.category) || categoryPick.supportScore < 0.35) {
            return null;
        }

        StringBuilder labelsBuilder = new StringBuilder();
        for (int i = 0; i < Math.min(6, scores.size()); i++) {
            if (i > 0) labelsBuilder.append(", ");
            labelsBuilder.append(scores.get(i).label)
                    .append(" (")
                    .append(Math.round(scores.get(i).score * 100))
                    .append("%)");
        }

        int confidencePct = (int) Math.round(categoryPick.supportScore * 100);
        result.put("category", categoryPick.category);
        result.put("condition", deriveCondition(categoryPick.supportScore));
        result.put("rawLabels", labelsBuilder.toString());
        result.put("caption", scores.get(0).label);
        result.put("confidence", confidencePct + "%");
        BrandModelResolver.Hint hint = resolveBrandModel(scores, categoryPick.category);
        result.put("suggestedTitle", hint.title());
        result.put("detectedBrand", hint.brand() != null ? hint.brand() : "");
        result.put("detectedModel", hint.model() != null ? hint.model() : "");
        result.put("estimateKeyword", hint.estimateKeyword());
        result.put("detectionSource", "huggingface");
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Core AI logic — calls ViT (base + large), maps labels, derives condition
    // ════════════════════════════════════════════════════════════════════════
    private Map<String, String> runAI(byte[] imageBytes) throws Exception {
        // Prefer your custom Roboflow models (camera + instruments) when configured
        if (roboflowVisionService.isConfigured()) {
            Map<String, String> rf = roboflowVisionService.analyze(imageBytes);
            if (rf != null && !rf.isEmpty()) {
                System.out.println("[AI] Using Roboflow: " + rf.get("detectionSource")
                        + " → " + rf.get("category") + " / " + rf.get("suggestedTitle"));
                return rf;
            }
            System.out.println("[AI] Roboflow returned no confident match — falling back to Hugging Face ViT");
        }

        System.out.println("[AI] Starting Hugging Face analysis (ViT fallback)");

        Map<String, String> result = new HashMap<>();
        result.put("category",   "Other");
        result.put("condition",  "Good");
        result.put("rawLabels",  "");
        result.put("caption",    "");
        result.put("confidence", "0%");
        result.put("suggestedTitle", "");
        result.put("detectedBrand", "");
        result.put("detectedModel", "");
        result.put("estimateKeyword", "");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        // Step 1: ViT base with retry
        List<LabelScore> scores = callVitWithRetry(client, imageBytes,
                "https://router.huggingface.co/hf-inference/models/google/vit-base-patch16-224",
                "ViT-base");

        // Step 2: if low confidence, also try ViT large and take the better result
        if (scores.isEmpty() || scores.get(0).score < 0.50) {
            System.out.println("[AI] ViT-base low confidence, trying ViT-large...");
            List<LabelScore> largeScores = callVitWithRetry(client, imageBytes,
                    "https://router.huggingface.co/hf-inference/models/google/vit-large-patch16-224",
                    "ViT-large");
            if (!largeScores.isEmpty() &&
                    (scores.isEmpty() || largeScores.get(0).score > scores.get(0).score)) {
                scores = largeScores;
                System.out.println("[AI] Using ViT-large result");
            }
        }

        if (scores.isEmpty()) {
            System.out.println("[AI] No scores returned from any model");
            BrandModelResolver.Hint hint = BrandModelResolver.resolve(List.of(), result.get("category"));
            result.put("suggestedTitle", hint.title());
            result.put("detectedBrand", "");
            result.put("detectedModel", "");
            result.put("estimateKeyword", hint.estimateKeyword());
            return result;
        }

        // Step 3: robust category mapping from top labels
        String topLabel = scores.get(0).label;
        CategoryPick categoryPick = pickCategoryFromLabels(scores, false);
        String category = categoryPick.category;

        // Step 4: derive condition from confidence + label coherence
        String condition = deriveCondition(categoryPick.supportScore);

        // Step 5: build display labels
        StringBuilder labelsBuilder = new StringBuilder();
        for (int i = 0; i < Math.min(6, scores.size()); i++) {
            if (i > 0) labelsBuilder.append(", ");
            labelsBuilder.append(scores.get(i).label)
                    .append(" (")
                    .append(Math.round(scores.get(i).score * 100))
                    .append("%)");
        }

        int confidencePct = (int) Math.round(categoryPick.supportScore * 100);
        System.out.println("[AI] category=" + category + " condition=" + condition +
                " confidence=" + confidencePct + "% topLabel=" + topLabel +
                " reason=" + categoryPick.reason);

        result.put("category",   category);
        result.put("condition",  condition);
        result.put("rawLabels",  labelsBuilder.toString());
        result.put("caption",    topLabel);
        result.put("confidence", confidencePct + "%");
        BrandModelResolver.Hint hint = resolveBrandModel(scores, category);
        String suggestedTitle = hint.title();
        if (BrandModelResolver.isGenericCategoryTitle(suggestedTitle, category)) {
            for (LabelScore ls : scores) {
                if (!BrandModelResolver.isNonItemLabel(ls.label)) {
                    String segment = firstLabelSegment(ls.label);
                    if (segment != null && !segment.isBlank()) {
                        suggestedTitle = toTitleWords(normalizeLabelForTitle(segment));
                        break;
                    }
                }
            }
        }
        result.put("suggestedTitle", suggestedTitle);
        result.put("detectedBrand", hint.brand() != null ? hint.brand() : "");
        result.put("detectedModel", hint.model() != null ? hint.model() : "");
        result.put("estimateKeyword", hint.estimateKeyword());
        result.put("detectionSource", "huggingface");
        return result;
    }

    private BrandModelResolver.Hint resolveBrandModel(List<LabelScore> scores, String category) {
        List<BrandModelResolver.LabelInput> inputs = new ArrayList<>();
        for (LabelScore ls : scores) {
            inputs.add(new BrandModelResolver.LabelInput(ls.label, ls.score));
        }
        return BrandModelResolver.resolve(inputs, category);
    }

    private String firstLabelSegment(String rawLabel) {
        if (rawLabel == null || rawLabel.isBlank()) return null;
        return rawLabel.split(",")[0].trim();
    }

    private String normalizeLabelForTitle(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').trim();
    }

    private String titleFallbackForCategory(String category) {
        if (category == null || category.isBlank() || "Other".equals(category)) {
            return "Hobby item";
        }
        return category + " item";
    }

    private String toTitleWords(String lowerSpacedPhrase) {
        String[] words = lowerSpacedPhrase.trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            if (w.length() == 1) {
                b.append(w.toUpperCase(Locale.ROOT));
            } else {
                b.append(Character.toUpperCase(w.charAt(0)))
                        .append(w.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return b.length() == 0 ? "Hobby item" : b.toString();
    }

    // ── ViT call with up to 3 retries on 503 cold-start ──────────────────────
    private List<LabelScore> callVitWithRetry(HttpClient client, byte[] imageBytes,
                                              String modelUrl, String modelName) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                List<LabelScore> scores = callVit(client, imageBytes, modelUrl, modelName);
                if (!scores.isEmpty()) return scores;

                if (attempt < 3) {
                    System.out.println("[AI] " + modelName + " cold-starting, waiting 10s (attempt " + attempt + "/3)...");
                    Thread.sleep(10000);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[AI] " + modelName + " attempt " + attempt + " error: " + e.getMessage());
            }
        }
        return Collections.emptyList();
    }

    private List<LabelScore> callVit(HttpClient client, byte[] imageBytes,
                                     String modelUrl, String modelName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(modelUrl))
                .header("Authorization", "Bearer " + hfApiKey)
                .header("Content-Type", "application/octet-stream")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(imageBytes))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String body   = response.body();
        int    status = response.statusCode();
        System.out.println("[AI] " + modelName + " status=" + status +
                " body=" + body.substring(0, Math.min(body.length(), 120)));

        if (status == 503) return Collections.emptyList(); // cold start — retry

        List<LabelScore> scores = new ArrayList<>();
        if (status != 200) return scores;

        JsonNode root = objectMapper.readTree(body);
        if (!root.isArray()) return scores;

        for (JsonNode node : root) {
            if (!node.has("label")) continue;
            String label = node.get("label").asText("").trim();
            double score = node.has("score") ? node.get("score").asDouble(0.0) : 0.0;
            if (!label.isEmpty()) {
                scores.add(new LabelScore(label, score));
            }
        }
        scores.sort((a, b) -> Double.compare(b.score, a.score));
        return scores;
    }

    // ── Condition derivation from confidence ─────────────────────────────────
    // High confidence = item is visually clean and distinct = better condition
    // Low confidence  = cluttered/worn background = worse condition
    // Keep condition logic explainable and demo-safe:
    // confidence >80% => Good, >60% => Fair, else => Worn (Poor equivalent)
    private String deriveCondition(double confidenceScore) {
        if (confidenceScore > 0.95) return "Like New";
        if (confidenceScore > 0.80) return "Good";
        if (confidenceScore > 0.60) return "Fair";
        return "Worn";
    }

    private String mapSingleLabel(String label) {
        String lower = normalizeLabel(label);
        for (Map.Entry<String, String> entry : LABEL_TO_CATEGORY.entrySet()) {
            if (lower.contains(entry.getKey())) return entry.getValue();
        }
        if (lower.contains("camera") || lower.contains("lens") || lower.contains("dslr")) {
            return "Cameras";
        }
        if (lower.contains("guitar") || lower.contains("piano") || lower.contains("violin")
                || lower.contains("drum") || lower.contains("saxophone") || lower.contains("ukulele")
                || lower.contains("trumpet") || lower.contains("flute") || lower.contains("bass")) {
            return "Instruments";
        }
        return null;
    }

    private CategoryPick pickCategoryFromLabels(List<LabelScore> scores, boolean strict) {
        if (scores == null || scores.isEmpty()) {
            if (strict) {
                return new CategoryPick(null, 0.0, "No labels");
            }
            return new CategoryPick("Instruments", 0.0, "No labels — default Instruments");
        }

        // 1) Direct-priority keyword pass for highly recognizable hobby items.
        int limit = Math.min(10, scores.size());
        for (int i = 0; i < limit; i++) {
            String normalized = normalizeLabel(scores.get(i).label);
            for (String token : DIRECT_PRIORITY_KEYWORDS) {
                if (!normalized.contains(token)) continue;
                String mapped = mapSingleLabel(normalized);
                if (mapped != null && HobbyCategories.isAllowed(mapped)) {
                    return new CategoryPick(
                            mapped,
                            Math.max(scores.get(i).score, 0.72),
                            "Direct priority keyword: " + token
                    );
                }
            }
        }

        // 2) General weighted voting using all configured keywords.
        Map<String, Double> bucket = new HashMap<>();
        double bestSingleHit = 0.0;
        int mappedHits = 0;
        for (int i = 0; i < limit; i++) {
            LabelScore ls = scores.get(i);
            String normalized = normalizeLabel(ls.label);

            // Top labels matter more; small rank decay keeps signal explainable.
            double rankWeight = Math.max(0.45, 1.0 - (i * 0.08));

            for (Map.Entry<String, String> entry : LABEL_TO_CATEGORY.entrySet()) {
                if (!normalized.contains(entry.getKey())) continue;
                mappedHits++;
                String mappedCategory = entry.getValue();
                // Add a small floor so very tiny HF scores can still classify correctly
                // when the label text clearly matches a known hobby keyword.
                double baseScore = Math.max(ls.score, 0.10);
                double weighted = baseScore * rankWeight;
                bucket.merge(mappedCategory, weighted, Double::sum);
                bestSingleHit = Math.max(bestSingleHit, ls.score);
            }
        }

        if (bucket.isEmpty()) {
            if (strict) {
                return new CategoryPick(null, scores.get(0).score, "No mapped camera/instrument labels");
            }
            return new CategoryPick(guessAllowedCategory(scores), scores.get(0).score, "No mapped labels — guessed from top label");
        }

        Map.Entry<String, Double> winner = bucket.entrySet()
                .stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);
        if (winner == null) {
            return new CategoryPick(guessAllowedCategory(scores), scores.get(0).score, "No reliable mapped winner");
        }

        String category = winner.getKey();
        if (!HobbyCategories.isAllowed(category)) {
            category = guessAllowedCategory(scores);
        }
        double supportScore = Math.min(1.0, Math.max(bestSingleHit, winner.getValue()));
        if (mappedHits >= 2) {
            supportScore = Math.max(supportScore, 0.65);
        }

        // No hard "Other" fallback if we found a mapped hobby category.
        // This keeps auto-fill useful for prototype demos.
        return new CategoryPick(category, Math.max(supportScore, 0.62), "Weighted label consensus");
    }

    private String normalizeLabel(String label) {
        if (label == null) return "";
        String normalized = NON_ALNUM.matcher(label.toLowerCase()).replaceAll(" ").replaceAll("\\s+", " ").trim();
        for (Map.Entry<String, String> entry : LABEL_NORMALIZATION.entrySet()) {
            normalized = normalized.replace(entry.getKey(), entry.getValue());
        }
        return normalized;
    }

    // ── Save uploaded file to disk ────────────────────────────────────────────
    private String guessAllowedCategory(List<LabelScore> scores) {
        if (scores == null || scores.isEmpty()) return "Instruments";
        String top = normalizeLabel(scores.get(0).label);
        if (top.contains("camera") || top.contains("lens") || top.contains("dslr")) {
            return "Cameras";
        }
        return "Instruments";
    }

    private String saveGalleryFile(MultipartFile photo, Long itemId, int slot) throws IOException {
        UploadValidator.validateImage(photo);
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) Files.createDirectories(uploadPath);

        String extension = UploadValidator.safeExtension(photo);
        String filename  = "item_" + itemId + "_hobby" + slot + "_" + System.currentTimeMillis() + extension;
        Files.write(uploadPath.resolve(filename), photo.getBytes());
        return "/uploads/" + filename;
    }

    private String saveFile(MultipartFile photo, Long itemId) throws IOException {
        UploadValidator.validateImage(photo);
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) Files.createDirectories(uploadPath);

        String extension = UploadValidator.safeExtension(photo);
        String filename  = "item_" + itemId + "_" + System.currentTimeMillis() + extension;
        Files.write(uploadPath.resolve(filename), photo.getBytes());
        return "/uploads/" + filename;
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    // ── Simple value holder ───────────────────────────────────────────────────
    private static class LabelScore {
        final String label;
        final double score;
        LabelScore(String l, double s) { this.label = l; this.score = s; }
    }

    private static class CategoryPick {
        final String category;
        final double supportScore;
        final String reason;
        CategoryPick(String category, double supportScore, String reason) {
            this.category = category;
            this.supportScore = supportScore;
            this.reason = reason;
        }
    }
}