package com.exchange.orderbook.api;

import com.exchange.orderbook.api.dto.*;
import com.exchange.orderbook.domain.OrderStatus;
import com.exchange.orderbook.service.MatchingEngine;
import com.exchange.orderbook.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final MatchingEngine matchingEngine;

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        OrderService.OrderResult result = orderService.placeOrder(request);
        return OrderResponse.from(result.order(), result.trades());
    }

    @DeleteMapping("/orders/{id}")
    public OrderResponse cancelOrder(@PathVariable UUID id) {
        return OrderResponse.from(orderService.cancelOrder(id), orderService.tradesFor(id));
    }

    @GetMapping("/orders/{id}")
    public OrderResponse getOrder(@PathVariable UUID id) {
        return OrderResponse.from(orderService.getOrder(id), orderService.tradesFor(id));
    }

    @GetMapping("/orders")
    public Page<OrderResponse> listOrders(
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return orderService.listOrders(status, pageable)
            .map(o -> OrderResponse.from(o, java.util.List.of()));
    }

    @GetMapping("/orderbook")
    public OrderBookSnapshotResponse getOrderBook(
            @RequestParam(defaultValue = "10") int levels) {
        return OrderBookSnapshotResponse.of(
            OrderService.SYMBOL,
            matchingEngine.getBidDepth(levels),
            matchingEngine.getAskDepth(levels)
        );
    }

    @GetMapping("/trades")
    public Page<TradeResponse> getTrades(
            @PageableDefault(size = 20, sort = "executedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return orderService.getRecentTrades(pageable).map(TradeResponse::from);
    }
}
