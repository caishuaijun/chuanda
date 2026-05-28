package com.example.demo.service;

import com.example.demo.entity.Item;
import com.example.demo.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;

    public Page<Item> findAll(Pageable pageable) {
        return itemRepository.findAll(pageable);
    }

    public List<Item> search(String keyword) {
        return itemRepository.findByNameContaining(keyword);
    }

    public Item findById(Long id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Item not found with id: " + id));
    }

    @Transactional
    public Item create(Item item) {
        return itemRepository.save(item);
    }

    @Transactional
    public Item update(Long id, Item item) {
        Item existing = findById(id);
        existing.setName(item.getName());
        existing.setDescription(item.getDescription());
        return itemRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        itemRepository.deleteById(id);
    }
}
