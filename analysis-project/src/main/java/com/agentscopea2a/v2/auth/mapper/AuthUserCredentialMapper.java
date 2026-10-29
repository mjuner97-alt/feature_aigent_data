package com.agentscopea2a.v2.auth.mapper;

import com.agentscopea2a.v2.auth.entity.AuthUserCredential;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuthUserCredentialMapper {
    AuthUserCredential selectByUserId(@Param("userId") String userId);
    int upsertPassword(@Param("userId") String userId, @Param("passwordHash") String passwordHash);
    int updateLoginState(@Param("userId") String userId, @Param("loginFailures") int loginFailures,
                         @Param("lockedUntil") java.time.LocalDateTime lockedUntil);
}
