package com.ceos23.cgv_clone.store.service.impl;

import com.ceos23.cgv_clone.global.exception.CustomException;
import com.ceos23.cgv_clone.global.response.ErrorCode;
import com.ceos23.cgv_clone.payment.service.PaymentService;
import com.ceos23.cgv_clone.store.dto.request.OrderRequest;
import com.ceos23.cgv_clone.store.dto.response.InventoryResponse;
import com.ceos23.cgv_clone.store.dto.response.OrderResponse;
import com.ceos23.cgv_clone.store.entity.*;
import com.ceos23.cgv_clone.store.repository.InventoryRepository;
import com.ceos23.cgv_clone.store.repository.OrderRepository;
import com.ceos23.cgv_clone.store.repository.StoreRepository;
import com.ceos23.cgv_clone.store.service.OrderService;
import com.ceos23.cgv_clone.user.entity.User;
import com.ceos23.cgv_clone.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static java.time.LocalDate.*;
import static java.time.format.DateTimeFormatter.*;
import static java.util.UUID.*;

@Service("orderServicePessimistic")
@RequiredArgsConstructor
public class OrderServicePessimistic implements OrderService {

    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public OrderResponse createOrder(Long userId, Long storeId, OrderRequest request) {
        PendingOrder pendingOrder = Objects.requireNonNull(transactionTemplate.execute(status ->
                createPendingOrder(userId, storeId, request)
        ));

        try {
            paymentService.pay(pendingOrder.paymentId(), pendingOrder.orderName(), pendingOrder.totalPrice());
        } catch (Exception e) {
            transactionTemplate.executeWithoutResult(status -> cancelPendingOrder(pendingOrder.orderId()));
            throw e;
        }

        return Objects.requireNonNull(transactionTemplate.execute(status ->
                completePayment(pendingOrder.orderId())
        ));
    }

    private PendingOrder createPendingOrder(Long userId, Long storeId, OrderRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        List<OrderRequest.OrderItemRequest> items = sortItems(request.getItems());

        List<Inventory> inventories = validateAndDecreaseStock(store, items);
        int totalPrice = calculateTotalPrice(inventories, items);

        String paymentId = generatePaymentId();
        String orderName = buildOrderName(inventories);

        Order order = Order.createPending(user, store, paymentId, totalPrice);

        addOrderItems(order, inventories, items);
        orderRepository.save(order);

        return new PendingOrder(order.getId(), paymentId, orderName, totalPrice);
    }

    private OrderResponse completePayment(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_NOT_FOUND));

        order.completePayment();

        return OrderResponse.from(order);
    }

    private void cancelPendingOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_NOT_FOUND));

        restoreInventories(order);
        order.cancelPending();
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_NOT_FOUND));

        verifyOrderOwner(userId, order);

        paymentService.cancel(order.getPaymentId());

        restoreInventories(order);
        order.cancel();

        return OrderResponse.from(order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryResponse> getInventories(Long storeId) {
        storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        List<Inventory> response = inventoryRepository.findAllByStore_Id(storeId);

        return response.stream()
                .map(InventoryResponse::from)
                .toList();
    }

    private List<OrderRequest.OrderItemRequest> sortItems(List<OrderRequest.OrderItemRequest> items) {
        return items.stream()
                .sorted(Comparator.comparing(OrderRequest.OrderItemRequest::getInventoryId))
                .toList();
    }

    private void verifyOrderOwner(Long userId, Order order) {
        if(!order.getUser().getId().equals(userId)) {
            throw new CustomException(ErrorCode.INVALID_ORDER_OWNER);
        }
    }

    private void restoreInventories(Order order) {
        List<OrderItem> items = order.getOrderItems().stream()
                .sorted(Comparator.comparing(oi -> oi.getInventory().getId()))
                .toList();

        for (OrderItem item : items) {
            Inventory inv = inventoryRepository.findByIdWithPessimisticLock(item.getInventory().getId())
                    .orElseThrow(() -> new CustomException(ErrorCode.ITEM_NOT_FOUND));

            inv.increase(item.getQuantity());
        }
    }

    private List<Inventory> validateAndDecreaseStock(Store store, List<OrderRequest.OrderItemRequest> items) {
        List<Inventory> inventories = new ArrayList<>();

        for (OrderRequest.OrderItemRequest item : items) {
            Inventory inventory = inventoryRepository.findByIdWithPessimisticLock(item.getInventoryId())
                    .orElseThrow(() -> new CustomException(ErrorCode.ITEM_NOT_FOUND));

            if (!inventory.getStore().getId().equals(store.getId())) {
                throw new CustomException(ErrorCode.INVALID_INVENTORY);
            }

            inventory.decrease(item.getQuantity());
            inventories.add(inventory);
        }

        return inventories;
    }

    private int calculateTotalPrice(List<Inventory> inventories, List<OrderRequest.OrderItemRequest> items) {
        int totalPrice = 0;
        for (int i = 0; i < items.size(); i++) {
            totalPrice += inventories.get(i).getMenu().getPrice() * items.get(i).getQuantity();
        }
        return totalPrice;
    }

    private void addOrderItems(Order order, List<Inventory> inventories, List<OrderRequest.OrderItemRequest> items) {
        for (int i = 0; i < items.size(); i++) {
            order.addOrderItem(OrderItem.create(order, inventories.get(i), items.get(i).getQuantity()));
        }
    }

    private String generatePaymentId() {
        return now().format(BASIC_ISO_DATE) + "_" + randomUUID().toString().substring(0, 8);
    }

    private String buildOrderName(List<Inventory> inventories) {
        String first = inventories.getFirst().getMenu().getName();
        return inventories.size() == 1 ? first : first + " 외 " + (inventories.size() - 1) + "건";
    }

    private record PendingOrder(Long orderId, String paymentId, String orderName, int totalPrice) {
    }

}
