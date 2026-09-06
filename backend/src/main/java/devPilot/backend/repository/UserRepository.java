package devPilot.backend.repository;

import devPilot.backend.entity.User;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
Optional<User> findByGithubId(long githubId);
     
}