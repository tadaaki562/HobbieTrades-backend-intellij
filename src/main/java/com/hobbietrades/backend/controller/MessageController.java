package com.hobbietrades.backend.controller;

import com.hobbietrades.backend.model.Message;
import com.hobbietrades.backend.model.Trade;
import com.hobbietrades.backend.model.User;
import com.hobbietrades.backend.repository.MessageRepository;
import com.hobbietrades.backend.repository.TradeRepository;
import com.hobbietrades.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private UserRepository userRepository;

    // GET /api/messages/trade/{tradeId} — get all messages for a trade
    @GetMapping("/trade/{tradeId}")
    public ResponseEntity<List<Message>> getMessages(@PathVariable Long tradeId) {
        return ResponseEntity.ok(
                messageRepository.findByTradeIdOrderBySentAtAsc(tradeId)
        );
    }

    // POST /api/messages — send a message
    @PostMapping
    public ResponseEntity<Map<String, Object>> sendMessage(
            @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();

        try {
            Long tradeId  = Long.parseLong(body.get("tradeId").toString());
            Long senderId = Long.parseLong(body.get("senderId").toString());
            String content = body.get("content").toString().trim();

            if (content.isEmpty()) {
                response.put("success", false);
                response.put("message", "Message content cannot be empty.");
                return ResponseEntity.badRequest().body(response);
            }

            Optional<Trade> trade   = tradeRepository.findById(tradeId);
            Optional<User>  sender  = userRepository.findById(senderId);

            if (trade.isEmpty() || sender.isEmpty()) {
                response.put("success", false);
                response.put("message", "Invalid trade or sender ID.");
                return ResponseEntity.badRequest().body(response);
            }

            Message message = new Message();
            message.setTrade(trade.get());
            message.setSender(sender.get());
            message.setContent(content);

            messageRepository.save(message);

            response.put("success", true);
            response.put("messageId", message.getId());
            response.put("sentAt", message.getSentAt().toString());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Error sending message: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}