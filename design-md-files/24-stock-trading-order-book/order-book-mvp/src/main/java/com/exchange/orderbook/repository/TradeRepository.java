package com.exchange.orderbook.repository;

import com.exchange.orderbook.domain.Trade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TradeRepository extends JpaRepository<Trade, UUID> {

    Page<Trade> findBySymbolOrderByExecutedAtDesc(String symbol, Pageable pageable);

    List<Trade> findByBuyOrderIdOrSellOrderIdOrderByExecutedAtAsc(UUID buyOrderId, UUID sellOrderId);
}
