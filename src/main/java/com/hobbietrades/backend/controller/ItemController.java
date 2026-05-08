package com.hobbietrades.backend.controller;

import com.hobbietrades.backend.model.Item;
import com.hobbietrades.backend.model.User;
import com.hobbietrades.backend.repository.ItemRepository;
import com.hobbietrades.backend.repository.UserRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/items")
@CrossOrigin(origins = "*")
public class ItemController {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private UserRepository userRepository;

    // GET /api/items — get all available items
    @GetMapping
    public ResponseEntity<List<Item>> getAllItems() {
        return ResponseEntity.ok(itemRepository.findByIsAvailableTrue());
    }

    // GET /api/items/{id} — get single item
    @GetMapping("/{id}")
    public ResponseEntity<Item> getItem(@PathVariable Long id) {
        Optional<Item> item = itemRepository.findById(id);
        return item.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // GET /api/items/category/{category}
    @GetMapping("/category/{category}")
    public ResponseEntity<List<Item>> getByCategory(@PathVariable String category) {
        return ResponseEntity.ok(itemRepository.findByCategoryAndIsAvailableTrue(category));
    }

    // GET /api/items/search?q=guitar
    @GetMapping("/search")
    public ResponseEntity<List<Item>> search(@RequestParam String q) {
        return ResponseEntity.ok(
                itemRepository.findByTitleContainingIgnoreCaseAndIsAvailableTrue(q)
        );
    }

    // GET /api/items/user/{userId}
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Item>> getByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(itemRepository.findByUserId(userId));
    }

    // POST /api/items — create new listing
    @PostMapping
    public ResponseEntity<Map<String, Object>> createItem(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();

        Long userId = Long.parseLong(body.get("userId").toString());
        Optional<User> userOpt = userRepository.findById(userId);

        if (userOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "User not found.");
            return ResponseEntity.badRequest().body(response);
        }

        Item item = new Item();
        item.setUser(userOpt.get());
        item.setTitle(body.get("title").toString());
        item.setDescription(body.get("description").toString());
        item.setCategory(body.get("category").toString());
        item.setConditionLabel(body.get("conditionLabel").toString());
        item.setEstimatedValue(new BigDecimal(body.get("estimatedValue").toString()));
        item.setLookingFor(body.get("lookingFor").toString());
        item.setLocation(body.get("location").toString());
        item.setIsAvailable(true);

        itemRepository.save(item);

        response.put("success", true);
        response.put("message", "Item listed successfully!");
        response.put("itemId", item.getId());
        return ResponseEntity.ok(response);
    }

    // DELETE /api/items/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteItem(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        Optional<Item> itemOpt = itemRepository.findById(id);
        if (itemOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "Item not found.");
            return ResponseEntity.badRequest().body(response);
        }
        Item item = itemOpt.get();
        item.setIsAvailable(false);
        itemRepository.save(item);
        response.put("success", true);
        response.put("message", "Item removed.");
        return ResponseEntity.ok(response);
    }
}