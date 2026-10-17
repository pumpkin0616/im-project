package com.example.im.conversation;

import com.example.im.common.ApiBusinessException;
import com.example.im.config.ImProperties;
import com.example.im.conversation.ConversationDtos.*;
import com.example.im.message.infrastructure.UuidV7Generator;
import com.example.im.routing.ConversationRouteResolver;
import com.example.im.routing.MessageTableNames;
import com.example.im.routing.ResolvedRoute;
import com.example.im.routing.UuidCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
final class ConversationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final UuidV7Generator ids;
    private final ConversationRouteResolver routes;
    private final String localShard;
    private final ObjectMapper objectMapper;

    ConversationService(JdbcTemplate jdbc, TransactionTemplate transactions, UuidV7Generator ids,
                        ConversationRouteResolver routes, ImProperties properties, ObjectMapper objectMapper) {
        this.jdbc=jdbc; this.transactions=transactions; this.ids=ids; this.routes=routes;
        this.localShard=properties.routing().localShardId();
        this.objectMapper=objectMapper;
    }

    ConversationView createDirect(UUID user, UUID peer) {
        if (user.equals(peer)) throw error("CANNOT_CHAT_SELF","Cannot create a direct conversation with yourself",422);
        requireActiveUsers(List.of(user,peer));
        if (!exists("SELECT COUNT(*) FROM im_control.friendships WHERE user_id=? AND friend_id=?",b(user),b(peer)))
            throw error("FRIENDSHIP_REQUIRED","Direct conversations require an active friendship",422);
        byte[] key=directKey(user,peer);
        UUID existing=findDirect(key);
        if(existing!=null) return get(user,existing,true);
        try {
            UUID id=localConversationId(); ResolvedRoute route=routes.resolve(id); String c=conversations(route); String m=members(route);
            transactions.executeWithoutResult(status->{
                Instant now=Instant.now();
                jdbc.update("INSERT INTO im_control.direct_conversations(direct_key,conversation_id,created_at) VALUES(?,?,?)",key,b(id),Timestamp.from(now));
                jdbc.update("INSERT INTO "+c+"(id,slot_id,route_epoch,write_state,type,direct_key,status,delivery_mode,last_seq,min_available_seq,member_version,created_at,updated_at) VALUES(?,?,?,1,1,?,1,1,0,1,1,?,?)",
                        b(id),route.slotId(),route.routeEpoch(),key,Timestamp.from(now),Timestamp.from(now));
                insertMember(m,id,user,route.slotId(),1,1,now);
                insertMember(m,id,peer,route.slotId(),1,1,now);
                upsertDirectory(user,id,1,1,now); upsertDirectory(peer,id,1,1,now);
                insertEvent(route,id,"CONVERSATION_CREATED",Map.of("type","DIRECT","memberVersion",1),now);
            });
            return get(user,id,true);
        } catch (DuplicateKeyException race) {
            UUID winner=findDirect(key);
            if(winner==null) throw race;
            return get(user,winner,true);
        }
    }

    ConversationView createGroup(UUID owner, CreateGroupRequest request) {
        LinkedHashSet<UUID> all=new LinkedHashSet<>(request.memberIds()); all.add(owner);
        if(all.size()>500) throw error("GROUP_TOO_LARGE","A group can contain at most 500 initial members",422);
        requireActiveUsers(List.copyOf(all));
        UUID id=localConversationId(); ResolvedRoute route=routes.resolve(id); String c=conversations(route); String m=members(route);
        transactions.executeWithoutResult(status->{
            Instant now=Instant.now();
            jdbc.update("INSERT INTO "+c+"(id,slot_id,route_epoch,write_state,type,title,owner_id,status,delivery_mode,last_seq,min_available_seq,member_version,created_at,updated_at) VALUES(?,?,?,1,2,?,?,1,1,0,1,1,?,?)",
                    b(id),route.slotId(),route.routeEpoch(),request.title().trim(),b(owner),Timestamp.from(now),Timestamp.from(now));
            for(UUID member:all) insertMember(m,id,member,route.slotId(),member.equals(owner)?3:1,1,now);
            for(UUID member:all) upsertDirectory(member,id,1,1,now);
            insertEvent(route,id,"CONVERSATION_CREATED",Map.of("type","GROUP","memberVersion",1),now);
        });
        return get(owner,id,true);
    }

    ConversationPage list(UUID user,int limit,String cursor) {
        DirectoryCursor parsed=decodeCursor(cursor);
        String filter=parsed==null?"":" AND (last_activity_at < ? OR (last_activity_at = ? AND conversation_id > ?))";
        List<DirectoryEntry> entries;
        if(parsed==null) {
            entries=jdbc.query("SELECT conversation_id,last_activity_at FROM im_directory.user_conversations WHERE user_id=? AND status=1 ORDER BY last_activity_at DESC,conversation_id LIMIT ?",
                    (rs,n)->new DirectoryEntry(UuidCodec.fromBytes(rs.getBytes(1)),rs.getTimestamp(2).toInstant()),b(user),limit+1);
        } else {
            Timestamp time=Timestamp.from(parsed.activity());
            entries=jdbc.query("SELECT conversation_id,last_activity_at FROM im_directory.user_conversations WHERE user_id=? AND status=1"+filter+" ORDER BY last_activity_at DESC,conversation_id LIMIT ?",
                    (rs,n)->new DirectoryEntry(UuidCodec.fromBytes(rs.getBytes(1)),rs.getTimestamp(2).toInstant()),b(user),time,time,b(parsed.id()),limit+1);
        }
        boolean hasMore=entries.size()>limit;
        if(hasMore) entries=new ArrayList<>(entries.subList(0,limit));
        List<ConversationView> result=new ArrayList<>();
        for(DirectoryEntry entry:entries) { try { result.add(get(user,entry.id(),false)); } catch(ApiBusinessException ignored) { } }
        String next=hasMore&&!entries.isEmpty()?encodeCursor(entries.getLast()):null;
        return new ConversationPage(result,hasMore,next);
    }

    ConversationView get(UUID user,UUID id,boolean includeMembers) {
        ResolvedRoute route=localRoute(id); String c=conversations(route); String m=members(route);
        MemberState mine=member(m,id,user);
        if(mine==null||mine.status()!=1) throw error("NOT_A_MEMBER","User is not an active conversation member",403);
        List<ConversationView> values=jdbc.query("SELECT id,type,title,owner_id,status,last_seq,min_available_seq,member_version,last_message_at,created_at FROM "+c+" WHERE id=?",
                (rs,n)->new ConversationView(UuidCodec.fromBytes(rs.getBytes("id")),typeName(rs.getInt("type")),rs.getString("title"),
                        uuid(rs.getBytes("owner_id")),statusName(rs.getInt("status")),roleName(mine.role()),rs.getLong("last_seq"),
                        rs.getLong("min_available_seq"),rs.getLong("member_version"),memberCount(m,id),instant(rs.getTimestamp("last_message_at")),
                        rs.getTimestamp("created_at").toInstant(),includeMembers?members(m,id):null),b(id));
        if(values.isEmpty()) throw error("CONVERSATION_NOT_FOUND","Conversation does not exist",404);
        return values.getFirst();
    }

    ConversationView updateTitle(UUID actor,UUID id,String title) {
        mutate(id,(route,c,m,state)->{
            MemberState mine=requireMember(m,id,actor); requireGroup(state); requireManager(mine);
            jdbc.update("UPDATE "+c+" SET title=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",title.trim(),b(id));
            insertEvent(route,id,"CONVERSATION_UPDATED",Map.of("title",title.trim()),Instant.now());
        });
        return get(actor,id,true);
    }

    ConversationView addMembers(UUID actor,UUID id,List<UUID> requested) {
        LinkedHashSet<UUID> users=new LinkedHashSet<>(requested); requireActiveUsers(List.copyOf(users));
        mutate(id,(route,c,m,state)->{
            MemberState mine=requireMember(m,id,actor); requireGroup(state); requireManager(mine);
            long version=state.memberVersion()+1; Instant now=Instant.now();
            for(UUID user:users) {
                MemberState existing = member(m, id, user);
                if (existing != null && existing.status() == 1) {
                    continue;
                }
                jdbc.update("INSERT INTO "+m+"(conversation_id,user_id,slot_id,role,status,membership_version,joined_seq,last_read_seq,joined_at,left_at) VALUES(?,?,?,1,1,?,?,0,?,NULL) ON DUPLICATE KEY UPDATE role=1,status=1,membership_version=VALUES(membership_version),joined_seq=VALUES(joined_seq),joined_at=VALUES(joined_at),left_at=NULL",
                        b(id),b(user),route.slotId(),version,state.lastSeq(),Timestamp.from(now));
                upsertDirectory(user,id,version,1,now);
            }
            jdbc.update("UPDATE "+c+" SET member_version=?,updated_at=? WHERE id=?",version,Timestamp.from(now),b(id));
            insertEvent(route,id,"MEMBERSHIP_CHANGED",Map.of("memberVersion",version,"operation","ADD"),now);
        });
        return get(actor,id,true);
    }

    void removeMember(UUID actor,UUID id,UUID target) {
        mutate(id,(route,c,m,state)->{
            MemberState mine=requireMember(m,id,actor), other=requireMember(m,id,target); requireGroup(state); requireManager(mine);
            if(other.role()==3) throw error("OWNER_PROTECTED","The group owner cannot be removed",422);
            if(mine.role()==2&&other.role()>=2) throw error("INSUFFICIENT_ROLE","An administrator cannot remove another administrator",403);
            deactivate(c,m,id,target,state.memberVersion()+1,2);
            insertEvent(route,id,"MEMBERSHIP_CHANGED",Map.of("memberVersion",state.memberVersion()+1,"operation","REMOVE","userId",target),Instant.now());
        });
    }

    ConversationView updateRole(UUID actor,UUID id,UUID target,Role role) {
        mutate(id,(route,c,m,state)->{
            MemberState mine=requireMember(m,id,actor),other=requireMember(m,id,target); requireGroup(state);
            if(mine.role()!=3) throw error("OWNER_REQUIRED","Only the group owner can change roles",403);
            if(other.role()==3) throw error("OWNER_PROTECTED","The owner role cannot be changed",422);
            long version=state.memberVersion()+1;
            jdbc.update("UPDATE "+m+" SET role=?,membership_version=? WHERE conversation_id=? AND user_id=? AND status=1",
                    role==Role.ADMIN?2:1,version,b(id),b(target));
            jdbc.update("UPDATE "+c+" SET member_version=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",version,b(id));
            insertEvent(route,id,"MEMBERSHIP_CHANGED",Map.of("memberVersion",version,"operation","ROLE","userId",target,"role",role.name()),Instant.now());
        });
        return get(actor,id,true);
    }

    void leave(UUID actor,UUID id) {
        mutate(id,(route,c,m,state)->{
            MemberState mine=requireMember(m,id,actor); requireGroup(state);
            if(mine.role()==3) throw error("OWNER_CANNOT_LEAVE","The group owner must dissolve the group",422);
            deactivate(c,m,id,actor,state.memberVersion()+1,2);
            insertEvent(route,id,"MEMBERSHIP_CHANGED",Map.of("memberVersion",state.memberVersion()+1,"operation","LEAVE","userId",actor),Instant.now());
        });
    }

    void dissolve(UUID actor,UUID id) {
        mutate(id,(route,c,m,state)->{
            MemberState mine=requireMember(m,id,actor); requireGroup(state);
            if(mine.role()!=3) throw error("OWNER_REQUIRED","Only the group owner can dissolve the group",403);
            long version=state.memberVersion()+1;
            jdbc.update("UPDATE "+c+" SET status=2,write_state=2,member_version=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",version,b(id));
            jdbc.update("UPDATE "+m+" SET status=2,membership_version=?,left_at=UTC_TIMESTAMP(3) WHERE conversation_id=? AND status=1",version,b(id));
            jdbc.update("UPDATE im_directory.user_conversations SET status=3,membership_version=?,updated_at=UTC_TIMESTAMP(3) WHERE conversation_id=?",version,b(id));
            insertEvent(route,id,"CONVERSATION_DISSOLVED",Map.of("memberVersion",version),Instant.now());
        });
    }

    private void mutate(UUID id,Mutation mutation) {
        ResolvedRoute route=localRoute(id); String c=conversations(route),m=members(route);
        transactions.executeWithoutResult(tx->{
            List<ConversationState> rows=jdbc.query("SELECT type,status,last_seq,member_version FROM "+c+" WHERE id=? FOR UPDATE",
                    (rs,n)->new ConversationState(rs.getInt(1),rs.getInt(2),rs.getLong(3),rs.getLong(4)),b(id));
            if(rows.isEmpty()) throw error("CONVERSATION_NOT_FOUND","Conversation does not exist",404);
            if(rows.getFirst().status()!=1) throw error("CONVERSATION_CLOSED","Conversation is not active",422);
            mutation.apply(route,c,m,rows.getFirst());
        });
    }

    private void deactivate(String c,String m,UUID id,UUID target,long version,int directoryStatus) {
        jdbc.update("UPDATE "+m+" SET status=2,membership_version=?,left_at=UTC_TIMESTAMP(3) WHERE conversation_id=? AND user_id=? AND status=1",version,b(id),b(target));
        jdbc.update("UPDATE "+c+" SET member_version=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",version,b(id));
        jdbc.update("UPDATE im_directory.user_conversations SET status=?,membership_version=?,updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND conversation_id=?",directoryStatus,version,b(target),b(id));
    }

    private void insertMember(String table,UUID conversation,UUID user,int slot,int role,long version,Instant now) {
        jdbc.update("INSERT INTO "+table+"(conversation_id,user_id,slot_id,role,status,membership_version,joined_seq,last_read_seq,joined_at) VALUES(?,?,?,?,1,?,0,0,?)",
                b(conversation),b(user),slot,role,version,Timestamp.from(now));
    }
    private void upsertDirectory(UUID user,UUID conversation,long version,int status,Instant now) {
        jdbc.update("INSERT INTO im_directory.user_conversations(user_id,conversation_id,membership_version,status,last_activity_at,last_known_seq,updated_at) VALUES(?,?,?,?,?,0,?) ON DUPLICATE KEY UPDATE membership_version=VALUES(membership_version),status=VALUES(status),last_activity_at=VALUES(last_activity_at),updated_at=VALUES(updated_at)",
                b(user),b(conversation),version,status,Timestamp.from(now),Timestamp.from(now));
    }
    private void insertEvent(ResolvedRoute route,UUID conversation,String type,Map<String,Object> payload,Instant now) {
        String outbox=new MessageTableNames(route.tableGroup(),0).outbox();
        try {
            jdbc.update("INSERT INTO "+outbox+"(event_id,event_type,schema_version,slot_id,conversation_id,payload,status,available_at,created_at) VALUES(?,?,1,?,?,CAST(? AS JSON),0,?,?)",
                    b(ids.next()),type,route.slotId(),b(conversation),objectMapper.writeValueAsString(payload),Timestamp.from(now),Timestamp.from(now));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot serialize conversation event",exception);
        }
    }
    private MemberState requireMember(String table,UUID conversation,UUID user) {
        MemberState value=member(table,conversation,user); if(value==null||value.status()!=1) throw error("NOT_A_MEMBER","User is not an active conversation member",403); return value;
    }
    private MemberState member(String table,UUID conversation,UUID user) {
        List<MemberState> rows=jdbc.query("SELECT role,status FROM "+table+" WHERE conversation_id=? AND user_id=?",
                (rs,n)->new MemberState(rs.getInt(1),rs.getInt(2)),b(conversation),b(user)); return rows.isEmpty()?null:rows.getFirst();
    }
    private List<MemberView> members(String table,UUID id) {
        return jdbc.query("SELECT m.user_id,u.account,u.nickname,u.avatar_key,m.role,m.joined_seq,m.last_read_seq,m.joined_at FROM "+table+" m JOIN im_control.users u ON u.id=m.user_id WHERE m.conversation_id=? AND m.status=1 ORDER BY m.role DESC,m.joined_at,m.user_id",
                (rs,n)->new MemberView(UuidCodec.fromBytes(rs.getBytes(1)),rs.getString(2),rs.getString(3),rs.getString(4),roleName(rs.getInt(5)),rs.getLong(6),rs.getLong(7),rs.getTimestamp(8).toInstant()),b(id));
    }
    private int memberCount(String table,UUID id) { Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE conversation_id=? AND status=1",Integer.class,b(id)); return n==null?0:n; }
    private void requireManager(MemberState member){if(member.role()<2)throw error("MANAGER_REQUIRED","Group administrator permission is required",403);}
    private void requireGroup(ConversationState state){if(state.type()!=2)throw error("GROUP_REQUIRED","This operation is only valid for group conversations",422);}
    private void requireActiveUsers(List<UUID> users) {
        if(users.isEmpty()) return;
        String marks=String.join(",",users.stream().map(x->"?").toList());
        Object[] values=users.stream().map(this::b).toArray();
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM im_control.users WHERE status=1 AND id IN ("+marks+")",Integer.class,values);
        if(count==null||count!=users.size()) throw error("USER_NOT_FOUND","One or more users do not exist or are inactive",404);
    }
    private UUID localConversationId(){for(int i=0;i<10000;i++){UUID id=ids.next();if(routes.resolve(id).shardId().equals(localShard))return id;}throw new IllegalStateException("Cannot generate a conversation on local shard");}
    private ResolvedRoute localRoute(UUID id){ResolvedRoute route=routes.resolve(id);if(!route.shardId().equals(localShard))throw error("ROUTE_MISMATCH","Conversation belongs to "+route.shardId(),503);return route;}
    private String conversations(ResolvedRoute route){return new MessageTableNames(route.tableGroup(),0).conversations();}
    private String members(ResolvedRoute route){return new MessageTableNames(route.tableGroup(),0).members();}
    private UUID findDirect(byte[] key){List<UUID> rows=jdbc.query("SELECT conversation_id FROM im_control.direct_conversations WHERE direct_key=?",(rs,n)->UuidCodec.fromBytes(rs.getBytes(1)),key);return rows.isEmpty()?null:rows.getFirst();}
    private String encodeCursor(DirectoryEntry entry){String raw=entry.activity().toEpochMilli()+":"+entry.id();return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));}
    private DirectoryCursor decodeCursor(String cursor){
        if(cursor==null||cursor.isBlank())return null;
        try{String raw=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.US_ASCII);int split=raw.indexOf(':');if(split<1)throw new IllegalArgumentException();return new DirectoryCursor(Instant.ofEpochMilli(Long.parseLong(raw.substring(0,split))),UUID.fromString(raw.substring(split+1)));}
        catch(RuntimeException exception){throw error("INVALID_CURSOR","Conversation cursor is invalid",400);}
    }
    private byte[] directKey(UUID a,UUID b){try{List<UUID> sorted=new ArrayList<>(List.of(a,b));sorted.sort(Comparator.comparing(UUID::toString));ByteBuffer bytes=ByteBuffer.allocate(32);sorted.forEach(x->bytes.put(b(x)));return MessageDigest.getInstance("SHA-256").digest(bytes.array());}catch(Exception e){throw new IllegalStateException(e);}}
    private boolean exists(String sql,Object... p){Integer n=jdbc.queryForObject(sql,Integer.class,p);return n!=null&&n>0;}
    private byte[] b(UUID id){return UuidCodec.toBytes(id);} private UUID uuid(byte[] v){return v==null?null:UuidCodec.fromBytes(v);}
    private Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    private String typeName(int type){return type==1?"DIRECT":"GROUP";} private String statusName(int status){return status==1?"ACTIVE":"DISSOLVED";}
    private String roleName(int role){return switch(role){case 3->"OWNER";case 2->"ADMIN";default->"MEMBER";};}
    private ApiBusinessException error(String code,String message,int status){return new ApiBusinessException(code,message,status);}
    private record MemberState(int role,int status){} private record ConversationState(int type,int status,long lastSeq,long memberVersion){}
    private record DirectoryEntry(UUID id,Instant activity){} private record DirectoryCursor(Instant activity,UUID id){}
    @FunctionalInterface private interface Mutation{void apply(ResolvedRoute route,String conversations,String members,ConversationState state);}
}
