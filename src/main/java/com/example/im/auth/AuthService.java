package com.example.im.auth;

import com.example.im.auth.AuthDtos.*;
import com.example.im.common.ApiBusinessException;
import com.example.im.config.ImProperties;
import com.example.im.message.infrastructure.UuidV7Generator;
import com.example.im.routing.UuidCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
final class AuthService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwtEncoder;
    private final ImProperties.Auth config;
    private final UuidV7Generator ids;
    private final SecureRandom random = new SecureRandom();

    AuthService(JdbcTemplate jdbc, TransactionTemplate transactions, PasswordEncoder passwords,
                JwtEncoder jwtEncoder, ImProperties properties, UuidV7Generator ids) {
        this.jdbc = jdbc; this.transactions = transactions; this.passwords = passwords;
        this.jwtEncoder = jwtEncoder; this.config = properties.auth(); this.ids = ids;
    }

    TokenResponse register(RegisterRequest request) {
        String account = normalize(request.account());
        UUID userId = ids.next();
        Instant now = Instant.now();
        try {
            return transactions.execute(status -> {
                jdbc.update("INSERT INTO im_control.users(id,account,password_hash,nickname,status,auth_version,created_at,updated_at) VALUES(?,?,?,?,1,1,?,?)",
                        UuidCodec.toBytes(userId), account, passwords.encode(request.password()), request.nickname().trim(),
                        Timestamp.from(now), Timestamp.from(now));
                return createSession(new UserRow(userId, account, request.nickname().trim(), null, 1, 1, now), request.deviceId(), now);
            });
        } catch (DuplicateKeyException exception) {
            throw new ApiBusinessException("ACCOUNT_EXISTS", "Account is already registered", 409);
        }
    }

    TokenResponse login(LoginRequest request) {
        UserRow user = findByAccount(normalize(request.account()));
        if (user == null || !passwords.matches(request.password(), passwordHash(user.id()))) {
            throw new ApiBusinessException("INVALID_CREDENTIALS", "Account or password is incorrect", 401);
        }
        requireActive(user);
        return transactions.execute(status -> createSession(user, request.deviceId(), Instant.now()));
    }

    TokenResponse refresh(String refreshToken) {
        byte[] hash = hash(refreshToken);
        return transactions.execute(status -> {
            List<SessionRow> sessions = jdbc.query("SELECT id,user_id,device_id,expires_at FROM im_control.auth_sessions WHERE refresh_token_hash=? AND revoked_at IS NULL FOR UPDATE",
                    (rs,n) -> new SessionRow(UuidCodec.fromBytes(rs.getBytes(1)), UuidCodec.fromBytes(rs.getBytes(2)),
                            UuidCodec.fromBytes(rs.getBytes(3)), rs.getTimestamp(4).toInstant()), hash);
            if (sessions.isEmpty() || !sessions.getFirst().expiresAt().isAfter(Instant.now())) {
                throw new ApiBusinessException("INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired", 401);
            }
            SessionRow session = sessions.getFirst();
            UserRow user = findById(session.userId()); requireActive(user);
            String next = randomToken();
            Instant expiry = Instant.now().plus(config.refreshTokenTtl());
            jdbc.update("UPDATE im_control.auth_sessions SET refresh_token_hash=?,expires_at=? WHERE id=?",
                    hash(next), Timestamp.from(expiry), UuidCodec.toBytes(session.id()));
            return tokens(user, session.id(), session.deviceId(), next, Instant.now());
        });
    }

    void logout(String refreshToken, UUID authenticatedUser) {
        jdbc.update("UPDATE im_control.auth_sessions SET revoked_at=UTC_TIMESTAMP(3) WHERE refresh_token_hash=? AND user_id=? AND revoked_at IS NULL",
                hash(refreshToken), UuidCodec.toBytes(authenticatedUser));
    }

    UserView me(UUID id) { return view(requireUser(id)); }

    UserView update(UUID id, UpdateProfileRequest request) {
        int changed = jdbc.update("UPDATE im_control.users SET nickname=?,avatar_key=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status=1",
                request.nickname().trim(), blankToNull(request.avatarKey()), UuidCodec.toBytes(id));
        if (changed != 1) throw new ApiBusinessException("USER_NOT_FOUND", "User does not exist", 404);
        return me(id);
    }

    private TokenResponse createSession(UserRow user, UUID deviceId, Instant now) {
        UUID sessionId = ids.next(); String refresh = randomToken();
        jdbc.update("INSERT INTO im_control.auth_sessions(id,user_id,device_id,refresh_token_hash,expires_at,created_at) VALUES(?,?,?,?,?,?)",
                UuidCodec.toBytes(sessionId), UuidCodec.toBytes(user.id()), UuidCodec.toBytes(deviceId), hash(refresh),
                Timestamp.from(now.plus(config.refreshTokenTtl())), Timestamp.from(now));
        return tokens(user, sessionId, deviceId, refresh, now);
    }

    private TokenResponse tokens(UserRow user, UUID sessionId, UUID deviceId, String refresh, Instant now) {
        Instant expires = now.plus(config.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(config.issuer()).subject(user.id().toString())
                .issuedAt(now).expiresAt(expires).claim("sid", sessionId.toString())
                .claim("did", deviceId.toString()).claim("av", user.authVersion()).build();
        String access = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenResponse("Bearer", access, config.accessTokenTtl().toSeconds(), refresh, view(user));
    }

    private UserRow requireUser(UUID id) {
        UserRow user = findById(id);
        if (user == null) throw new ApiBusinessException("USER_NOT_FOUND", "User does not exist", 404);
        requireActive(user); return user;
    }
    private void requireActive(UserRow user) {
        if (user == null || user.status() != 1) throw new ApiBusinessException("ACCOUNT_DISABLED", "Account is not active", 403);
    }
    private UserRow findByAccount(String account) {
        List<UserRow> rows = jdbc.query("SELECT id,account,nickname,avatar_key,status,auth_version,created_at FROM im_control.users WHERE account=?",
                (rs,n)-> row(rs), account); return rows.isEmpty()?null:rows.getFirst();
    }
    private UserRow findById(UUID id) {
        List<UserRow> rows = jdbc.query("SELECT id,account,nickname,avatar_key,status,auth_version,created_at FROM im_control.users WHERE id=?",
                (rs,n)-> row(rs), UuidCodec.toBytes(id)); return rows.isEmpty()?null:rows.getFirst();
    }
    private UserRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserRow(UuidCodec.fromBytes(rs.getBytes("id")), rs.getString("account"), rs.getString("nickname"),
                rs.getString("avatar_key"), rs.getInt("status"), rs.getLong("auth_version"), rs.getTimestamp("created_at").toInstant());
    }
    private String passwordHash(UUID id) { return jdbc.queryForObject("SELECT password_hash FROM im_control.users WHERE id=?", String.class, UuidCodec.toBytes(id)); }
    private UserView view(UserRow u) { return new UserView(u.id(),u.account(),u.nickname(),u.avatarKey(),u.createdAt()); }
    private String randomToken() { byte[] bytes=new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private byte[] hash(String value) { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private String blankToNull(String value) { return value==null||value.isBlank()?null:value.trim(); }
    private record UserRow(UUID id,String account,String nickname,String avatarKey,int status,long authVersion,Instant createdAt) {}
    private record SessionRow(UUID id,UUID userId,UUID deviceId,Instant expiresAt) {}
}
