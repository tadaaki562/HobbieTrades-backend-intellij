package com.hobbietrades.backend.controller;

import com.hobbietrades.backend.service.PriceEstimationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class PriceController {

    @Autowired
    private PriceEstimationService priceEstimationService;

    // GET /api/estimate?category=Cameras&condition=Good&keyword=dslr camera
    @GetMapping("/estimate")
    public ResponseEntity<Map<String, Object>> estimatePrice(
            @RequestParam String category,
            @RequestParam String condition,
            @RequestParam(required = false, defaultValue = "") String keyword
    ) {
        Map<String, Object> result = priceEstimationService.estimatePrice(category, condition, keyword);
        return ResponseEntity.ok(result);
    }
}