package io.github.rladmstj.esmserver.model;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class Users {

    public Users() {} // 기본 생성자

    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    @Column(name="name", length = 50)
    private String name;

    @Column(name="info")
    private String info;


    // Getter & Setter
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getInfo() {
        return info;
    }

    public void setInfo(String info) {
        this.info = info;
    }
}