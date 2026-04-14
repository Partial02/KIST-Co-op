package io.github.rladmstj.esmserver.controller;

import io.github.rladmstj.esmserver.model.Users;
import io.github.rladmstj.esmserver.repository.UsersRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UsersController {

    @Autowired
    private UsersRepository usersRepository;

    // 전체 유저 목록
    @GetMapping
    public List<Users> getAllUsers() {
        return usersRepository.findAll();
    }

    // 특정 유저 조회
    @GetMapping("/{id}")
    public Users getUserById(@PathVariable String id) {
        return usersRepository.findById(id).orElse(null);
    }

    // 유저 등록
    @PostMapping
    public Users createUser(@RequestBody Users users) {
        return usersRepository.save(users);
    }

    // 유저 삭제
    @DeleteMapping("/{id}")
    public void deleteUser(@PathVariable String id) {
        usersRepository.deleteById(id);
    }
}

