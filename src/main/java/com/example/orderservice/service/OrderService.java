package com.example.orderservice.service;

import com.example.orderservice.dto.CreateOrderRequest;
import com.example.orderservice.dto.ProductDto;
import com.example.orderservice.model.Order;
import com.example.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final RestClient productServiceClient;

    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    public Optional<Order> getOrderById(Long id) {
        return orderRepository.findById(id);
    }

    public Order createOrder(CreateOrderRequest request) {
        log.info("Processing order for customer: {}, product: {}, quantity: {}",
                request.getCustomerName(), request.getProductId(), request.getQuantity());

        // Step 1: Fetch product details from product-service
        ProductDto product = fetchProduct(request.getProductId());

        // Step 2: Check stock
        if (product.getStockQuantity() < request.getQuantity()) {
            throw new IllegalStateException(
                    String.format("Insufficient stock for '%s'. Available: %d, Requested: %d",
                            product.getName(), product.getStockQuantity(), request.getQuantity())
            );
        }

        // Step 3: Reduce stock in product-service
        boolean stockReduced = reduceProductStock(request.getProductId(), request.getQuantity());
        if (!stockReduced) {
            throw new IllegalStateException("Failed to reduce stock. Order aborted.");
        }

        // Step 4: Compute price and save order
        BigDecimal totalPrice = product.getPrice()
                .multiply(BigDecimal.valueOf(request.getQuantity()));

        Order order = Order.builder()
                .customerName(request.getCustomerName())
                .productId(request.getProductId())
                .productName(product.getName())
                .quantity(request.getQuantity())
                .unitPrice(product.getPrice())
                .totalPrice(totalPrice)
                .status(Order.OrderStatus.CONFIRMED)
                .build();

        Order saved = orderRepository.save(order);
        log.info("✅ Order #{} created for {} - {} x{} = ${}",
                saved.getId(), saved.getCustomerName(), saved.getProductName(),
                saved.getQuantity(), saved.getTotalPrice());
        return saved;
    }

    private ProductDto fetchProduct(Long productId) {
        try {
            return productServiceClient.get()
                    .uri("/api/products/{id}", productId)
                    .retrieve()
                    .body(ProductDto.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalArgumentException("Product with ID " + productId + " not found");
        } catch (Exception e) {
            log.error("Failed to fetch product from product-service: {}", e.getMessage());
            throw new RuntimeException("Product service is unavailable. Please try again later.");
        }
    }

    private boolean reduceProductStock(Long productId, int quantity) {
        try {
            productServiceClient.put()
                    .uri("/api/products/{id}/reduce-stock?quantity={qty}", productId, quantity)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.error("Failed to reduce stock in product-service: {}", e.getMessage());
            return false;
        }
    }
}
