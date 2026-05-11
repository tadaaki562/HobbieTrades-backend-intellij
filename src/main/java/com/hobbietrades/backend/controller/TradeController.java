package com.hobbietrades.backend.controller;

import com.hobbietrades.backend.model.*;
import com.hobbietrades.backend.repository.*;
import com.hobbietrades.backend.service.MatchingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/trades")
@CrossOrigin(origins = "*")
public class TradeController {

    @Autowired private TradeRepository tradeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private MatchingService matchingService;
    @Autowired private MessageRepository messageRepository;
    @Autowired private ReviewRepository reviewRepository;

    // POST /api/trades — propose a trade
    @PostMapping
    public ResponseEntity<Map<String, Object>> proposeTrade(
            @RequestBody Map<String, Object> body) {

        Map<String, Object> response = new HashMap<>();

        Long proposerId     = Long.parseLong(body.get("proposerId").toString());
        Long receiverId     = Long.parseLong(body.get("receiverId").toString());
        Long offeredItemId  = Long.parseLong(body.get("offeredItemId").toString());
        Long requestedItemId = Long.parseLong(body.get("requestedItemId").toString());

        Optional<User> proposer      = userRepository.findById(proposerId);
        Optional<User> receiver      = userRepository.findById(receiverId);
        Optional<Item> offeredItem   = itemRepository.findById(offeredItemId);
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

    // GET /api/trades/user/{userId} — all trades for a user
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Trade>> getUserTrades(@PathVariable Long userId) {
        return ResponseEntity.ok(
                tradeRepository.findByProposerIdOrReceiverId(userId, userId)
        );
    }

    // PUT /api/trades/{id}/accept
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
        createStatusMessage(trade,
                trade.getReceiver(),
                "Trade accepted by " + safeName(trade.getReceiver()) +
                ". Waiting for both traders to confirm meetup completion.");
        response.put("success", true);
        response.put("message", "Trade accepted!");
        return ResponseEntity.ok(response);
    }

    // PUT /api/trades/{id}/confirm — both parties confirm → completed
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

        if (Boolean.TRUE.equals(trade.getProposerConfirmed())
                && Boolean.TRUE.equals(trade.getReceiverConfirmed())) {
            trade.setStatus("completed");

            User proposer = trade.getProposer();
            User receiver = trade.getReceiver();
            proposer.setTradeCount(proposer.getTradeCount() + 1);
            receiver.setTradeCount(receiver.getTradeCount() + 1);
            userRepository.save(proposer);
            userRepository.save(receiver);

            trade.getOfferedItem().setIsAvailable(false);
            trade.getRequestedItem().setIsAvailable(false);
            itemRepository.save(trade.getOfferedItem());
            itemRepository.save(trade.getRequestedItem());
            createStatusMessage(trade, trade.getReceiver(),
                    "Trade completed. Both traders confirmed the meetup.");
        } else {
            String waitingFor = trade.getProposerConfirmed()
                    ? safeName(trade.getReceiver())
                    : safeName(trade.getProposer());
            createStatusMessage(trade,
                    resolveUserById(trade, userId),
                    safeNameById(trade, userId) +
                    " confirmed meetup completion. Waiting for " + waitingFor + ".");
        }

        tradeRepository.save(trade);
        response.put("success", true);
        response.put("status",  trade.getStatus());
        response.put("message", "completed".equals(trade.getStatus())
                ? "Trade completed! Both parties confirmed."
                : "Confirmation recorded.");
        return ResponseEntity.ok(response);
    }

    /**
     * Remove a trade and its chat from the user's inbox. Only allowed for
     * completed or declined trades, and only for participants.
     */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteTradeForUser(
            @PathVariable Long id,
            @RequestParam Long userId) {

        Map<String, Object> response = new HashMap<>();
        Optional<Trade> tradeOpt = tradeRepository.findById(id);
        if (tradeOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "Trade not found.");
            return ResponseEntity.badRequest().body(response);
        }
        Trade trade = tradeOpt.get();
        boolean participant = trade.getProposer().getId().equals(userId)
                || trade.getReceiver().getId().equals(userId);
        if (!participant) {
            response.put("success", false);
            response.put("message", "You can only delete your own trades.");
            return ResponseEntity.status(403).body(response);
        }
        String st = trade.getStatus();
        if (!"completed".equals(st) && !"declined".equals(st)) {
            response.put("success", false);
            response.put("message", "Only completed or declined trades can be removed from your inbox.");
            return ResponseEntity.badRequest().body(response);
        }

        reviewRepository.deleteByTradeId(id);
        messageRepository.deleteByTradeId(id);
        tradeRepository.delete(trade);

        response.put("success", true);
        response.put("message", "Chat removed.");
        return ResponseEntity.ok(response);
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

    /**
     * GET /api/trades/matches?userId=1&limit=20
     *
     * FIX: old endpoint called matchingService.findMatches(itemId, userId) which
     * doesn't exist. Replaced with getMatchesForUser() — the actual method signature.
     * The itemId param is no longer needed; matching is user-scoped, not item-scoped.
     */
    @GetMapping("/matches")
    public ResponseEntity<List<Map<String, Object>>> getMatches(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "20") int limit) {

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) return ResponseEntity.notFound().build();

        List<MatchingService.MatchResult> matches =
                matchingService.getMatchesForUser(userOpt.get(), limit);

        List<Map<String, Object>> result = matches.stream().map(m -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("item",         m.getItem());
            entry.put("matchPercent", m.getMatchPercent());
            entry.put("matchReason",  m.getMatchReason());
            return entry;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/trades/for-you-signals?userId=1
     * Categories & keywords inferred from items the user traded for (For You hero).
     */
    @GetMapping("/for-you-signals")
    public ResponseEntity<Map<String, Object>> getForYouSignals(@RequestParam Long userId) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(matchingService.getForYouSignals(userOpt.get()));
    }

    private void createStatusMessage(Trade trade, User sender, String content) {
        Message message = new Message();
        message.setTrade(trade);
        message.setSender(sender != null ? sender : trade.getProposer());
        message.setContent(content);
        messageRepository.save(message);
    }

    private String safeName(User user) {
        return user != null && user.getName() != null && !user.getName().isBlank()
                ? user.getName()
                : "Trader";
    }

    private String safeNameById(Trade trade, Long userId) {
        if (trade.getProposer() != null && trade.getProposer().getId().equals(userId)) {
            return safeName(trade.getProposer());
        }
        if (trade.getReceiver() != null && trade.getReceiver().getId().equals(userId)) {
            return safeName(trade.getReceiver());
        }
        return "A trader";
    }

    private User resolveUserById(Trade trade, Long userId) {
        if (trade.getProposer() != null && trade.getProposer().getId().equals(userId)) {
            return trade.getProposer();
        }
        if (trade.getReceiver() != null && trade.getReceiver().getId().equals(userId)) {
            return trade.getReceiver();
        }
        return trade.getProposer();
    }
}