
package io.github.rladmstj.esmserver.repository;

        import io.github.rladmstj.esmserver.model.Users;
        import org.springframework.data.jpa.repository.JpaRepository;

public interface UsersRepository extends JpaRepository<Users, String> {
}

