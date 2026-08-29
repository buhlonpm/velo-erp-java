package com.velo.customer;

import com.velo.common.exception.NotFoundException;
import com.velo.customer.dto.CreateCustomerRequest;
import com.velo.customer.dto.CustomerResponse;
import com.velo.customer.dto.UpdateCustomerRequest;
import com.velo.rental.RentalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final RentalRepository rentalRepository;

    public List<CustomerResponse> findAll(String query) {
        return customerRepository.findAllByOrderByFullName().stream()
                .filter(customer -> matches(customer, query))
                .map(customer -> CustomerResponse.from(customer,
                        rentalRepository.countByCustomerId(customer.getId())))
                .toList();
    }

    @Transactional
    public CustomerResponse create(CreateCustomerRequest request) {
        Customer customer = new Customer();
        customer.setFullName(request.fullName());
        customer.setPhone(request.phone());
        customer.setEmail(request.email() != null ? request.email() : "");
        customer.setAddress(request.address() != null ? request.address() : "");
        customer.setNote(request.note() != null ? request.note() : "");
        return CustomerResponse.from(customerRepository.save(customer), 0);
    }

    @Transactional
    public CustomerResponse update(UUID id, UpdateCustomerRequest request) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Клиент не найден"));
        if (request.fullName() != null) {
            customer.setFullName(request.fullName());
        }
        if (request.phone() != null) {
            customer.setPhone(request.phone());
        }
        if (request.email() != null) {
            customer.setEmail(request.email());
        }
        if (request.address() != null) {
            customer.setAddress(request.address());
        }
        if (request.note() != null) {
            customer.setNote(request.note());
        }
        Customer saved = customerRepository.save(customer);
        return CustomerResponse.from(saved, rentalRepository.countByCustomerId(id));
    }

    private boolean matches(Customer customer, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.trim().toLowerCase();
        return customer.getFullName().toLowerCase().contains(q)
                || customer.getPhone().contains(q);
    }
}
