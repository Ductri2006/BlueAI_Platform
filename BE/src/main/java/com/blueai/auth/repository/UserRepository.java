package com.blueai.auth.repository;

import com.blueai.auth.model.User;
import com.blueai.auth.model.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("""
            select user from User user
             where (:includeDeleted = true or user.deletedAt is null)
               and (:status is null or user.status = :status)
               and (:search is null or lower(user.email) like lower(concat('%', :search, '%')) escape '\\'
                    or lower(user.fullName) like lower(concat('%', :search, '%')) escape '\\')
            """)
    Page<User> searchUsers(
            @Param("search") String search,
            @Param("status") UserStatus status,
            @Param("includeDeleted") boolean includeDeleted,
            Pageable pageable
    );
}
