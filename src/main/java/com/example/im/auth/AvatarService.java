package com.example.im.auth;

import com.example.im.common.ApiBusinessException;
import com.example.im.config.ImProperties;
import com.example.im.routing.UuidCodec;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
final class AvatarService {
    private static final long MAX_BYTES=5L*1024*1024;
    private static final Map<String,String> EXTENSIONS=Map.of(
            "image/jpeg",".jpg","image/png",".png","image/webp",".webp","image/gif",".gif");
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final Path root;

    AvatarService(JdbcTemplate jdbc,AuthService auth,ImProperties properties) {
        this.jdbc=jdbc;this.auth=auth;this.root=properties.storage().root().toAbsolutePath().normalize();
    }

    AuthDtos.UserView upload(UUID user,MultipartFile file) {
        String type=file.getContentType();String extension=EXTENSIONS.get(type);
        if(extension==null)throw error("INVALID_AVATAR_TYPE","Avatar must be JPEG, PNG, WebP, or GIF",422);
        if(file.isEmpty()||file.getSize()>MAX_BYTES)throw error("INVALID_AVATAR_SIZE","Avatar must be between 1 byte and 5 MB",422);
        String key="avatars/"+user+"/"+UUID.randomUUID()+extension;
        Path target=path(key),temporary=target.resolveSibling(target.getFileName()+".tmp");
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(temporary);
            Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        } catch(IOException exception) {
            try{Files.deleteIfExists(temporary);}catch(IOException ignored){}
            throw new IllegalStateException("Cannot store avatar",exception);
        }
        String previous=avatarKey(user);
        int changed=jdbc.update("UPDATE im_control.users SET avatar_key=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status=1",
                key,UuidCodec.toBytes(user));
        if(changed!=1)throw error("USER_NOT_FOUND","User does not exist",404);
        deleteFile(previous);
        return auth.me(user);
    }

    AvatarDownload download(UUID user) {
        String key=avatarKey(user);
        if(key==null)throw error("AVATAR_NOT_FOUND","Avatar does not exist",404);
        Path path=path(key);
        if(!Files.isRegularFile(path))throw error("AVATAR_NOT_FOUND","Avatar does not exist",404);
        String name=path.getFileName().toString().toLowerCase();
        String type=name.endsWith(".png")?"image/png":name.endsWith(".webp")?"image/webp":name.endsWith(".gif")?"image/gif":"image/jpeg";
        return new AvatarDownload(new FileSystemResource(path),type);
    }

    void delete(UUID user) {
        String previous=avatarKey(user);
        jdbc.update("UPDATE im_control.users SET avatar_key=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status=1",UuidCodec.toBytes(user));
        deleteFile(previous);
    }

    private String avatarKey(UUID user) {
        List<String> rows=jdbc.query("SELECT avatar_key FROM im_control.users WHERE id=? AND status=1",
                (rs,n)->rs.getString(1),UuidCodec.toBytes(user));
        if(rows.isEmpty())throw error("USER_NOT_FOUND","User does not exist",404);
        return rows.getFirst();
    }
    private Path path(String key){Path value=root.resolve(key).normalize();if(!value.startsWith(root))throw new IllegalStateException("Invalid avatar key");return value;}
    private void deleteFile(String key){if(key==null)return;try{Files.deleteIfExists(path(key));}catch(IOException ignored){}}
    private ApiBusinessException error(String code,String message,int status){return new ApiBusinessException(code,message,status);}
    record AvatarDownload(Resource resource,String contentType){}
}
