package com.hobbietrades.backend.controller;

import com.hobbietrades.backend.model.*;
import com.hobbietrades.backend.repository.*;
import com.hobbietrades.backend.service.MatchingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/trades")
@CrossOrigin(origins = "*")
public class TradeController {

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private MatchingService matchingService;

    // POST /api/trades — propose a trade
    @PostMapping
    public ResponseEntity<Map<String, Object>> proposeTrade(
            @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();

        Long proposerId = Long.parseLong(body.get("proposerId").toString());
        Long receiverId = Long.parseLong(body.get("receiverId").toString());
        Long offeredItemId = Long.parseLong(body.get("offeredItemId").toString());
        Long requestedItemId = Long.parseLong(body.get("requestedItemId").toString());

        Optional<User> proposer = userRepository.findById(proposerId);
        Optional<User> receiver = userRepository.findById(receiverId);
        Optional<Item> offeredItem = itemRepository.findById(offeredItemId);
        Optional<Item> requestedItem = itemRepository.findById(requestedItemId);

        if (proposer.isEmpty() || receiver.isEmpty() ||
                offeredItem.isEmpty() || requestedItem.isEmpty()) {
            response.put("success", false);
            response.put("message", "Invalid trade data.");
            return ResponseEntity.badRequest().body(response);
        }

        Trade trade = new Trade();
        trade.setProposer(proposer.get());
        trade.setReceiver(receiver.get());
        trade.setOfferedItem(offeredItem.get());
        trade.setRequestedItem(requestedItem.get());
        trade.setStatus("pending");

        tradeRepository.save(trade);

        response.put("success", true);
        response.put("message", "Trade proposal sent!");
        response.put("tradeId", trade.getId());
        return ResponseEntity.ok(response);
    }

    // GET /api/trades/user/{userId} — get all trades for a user
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Trade>> getUserTrades(@PathVariable Long userId) {
        return ResponseEntity.ok(
                tradeRepository.findByProposerIdOrReceiverId(userId, userId)
        );
    }

    // PUT /api/trades/{id}/accept — accept a trade
    @PutMapping("/{id}/accept")
    public ResponseEntity<Map<String, Object>> acceptTrade(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        Optional<Trade> tradeOpt = tradeRepository.findById(id);
        if (tradeOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "Trade not found.");
            return ResponseEntity.badRequest().body(response);
        }
        Trade trade = tradeOpt.get();
        trade.setStatus("accepted");
        tradeRepository.save(trade);
        response.put("success", true);
        response.put("message", "Trade accepted!");
        return ResponseEntity.ok(response);
    }

    // PUT /api/trades/{id}/confirm — confirm trade completed
    @PutMapping("/{id}/confirm")
    public ResponseEntity<Map<String, Object>> confirmTrade(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        Optional<Trade> tradeOpt = tradeRepository.findById(id);
        if (tradeOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "Trade not found.");
            return ResponseEntity.badRequest().body(response);
        }

        Trade trade = tradeOpt.get();
        Long userId = Long.parseLong(body.get("userId").toString());

        if (trade.getProposer().getId().equals(userId)) {
            trade.setProposerConfirmed(true);
        } else if (trade.getReceiver().getId().equals(userId)) {
            trade.setReceiverConfirmed(true);
        }

        // Both confirmed — mark as completed
        if (trade.getProposerConfirmed() && trade.getReceiverConfirmed()) {
            trade.setStatus("completed");

            // Update trade counts for both users
            User proposer = trade.getProposer();
            User receiver = trade.getReceiver();
            proposer.setTradeCount(proposer.getTradeCount() + 1);
            receiver.setTradeCount(receiver.getTradeCount() + 1);
            userRepository.save(proposer);
            userRepository.save(receiver);

            // Mark both items as unavailable
            trade.getOfferedItem().setIsAvailable(false);
            trade.getRequestedItem().setIsAvailable(false);
            itemRepository.save(trade.getOfferedItem());
            itemRepository.save(trade.getRequestedItem());
        }

        tradeRepository.save(trade);
        response.put("success", true);
        response.put("status", trade.getStatus());
        response.put("message", trade.getStatus().equals("completed") ?
                "Trade completed! Both parties confirmed." : "Confirmation recorded.");
        return ResponseEntity.ok(response);
    }

    // GET /api/trades/matches?itemId=1&userId=1
    @GetMapping("/matches")
    public ResponseEntity<List<Map<String, Object>>> getMatches(
            @RequestParam Long itemId,
            @RequestParam Long userId) {
        return ResponseEntity.ok(matchingService.findMatches(itemId, userId));
    }

    // PUT /api/trades/{id}/decline
    @PutMapping("/{id}/decline")
    public ResponseEntity<Map<String, Object>> declineTrade(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        Optional<Trade> tradeOpt = tradeRepository.findById(id);
        if (tradeOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "Trade not found.");
            return ResponseEntity.badRequest().body(response);
        }
        Trade trade = tradeOpt.get();
        trade.setStatus("declined");
        tradeRepository.save(trade);
        response.put("success", true);
        response.put("message", "Trade declined.");
        return ResponseEntity.ok(response);
    }
}