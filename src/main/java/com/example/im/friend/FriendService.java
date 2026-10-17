package com.example.im.friend;

import com.example.im.common.ApiBusinessException;
import com.example.im.friend.FriendDtos.*;
import com.example.im.message.infrastructure.UuidV7Generator;
import com.example.im.delivery.RealtimeDeliveryRouter;
import com.example.im.routing.UuidCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
final class FriendService {
    private static final Logger log=LoggerFactory.getLogger(FriendService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final UuidV7Generator ids;
    private final RealtimeDeliveryRouter realtime;
    FriendService(JdbcTemplate jdbc, TransactionTemplate transactions, UuidV7Generator ids,
                  RealtimeDeliveryRouter realtime) {
        this.jdbc=jdbc; this.transactions=transactions; this.ids=ids; this.realtime=realtime;
    }

    RequestView create(UUID requester, CreateRequest request) {
        if (request.recipientId()==null) throw new ApiBusinessException("INVALID_REQUEST","recipientId is required",400);
        if (requester.equals(request.recipientId())) throw new ApiBusinessException("CANNOT_FRIEND_SELF","Cannot add yourself",422);
        requireActiveUser(request.recipientId());
        if (exists("SELECT COUNT(*) FROM im_control.user_blocks WHERE (user_id=? AND blocked_user_id=?) OR (user_id=? AND blocked_user_id=?)",
                requester,request.recipientId(),request.recipientId(),requester)) throw new ApiBusinessException("USER_BLOCKED","Friend request is blocked",422);
        if (exists("SELECT COUNT(*) FROM im_control.friendships WHERE user_id=? AND friend_id=?",requester,request.recipientId()))
            throw new ApiBusinessException("ALREADY_FRIENDS","Users are already friends",409);
        if (exists("SELECT COUNT(*) FROM im_control.friend_requests WHERE status=0 AND ((requester_id=? AND recipient_id=?) OR (requester_id=? AND recipient_id=?))",
                requester,request.recipientId(),request.recipientId(),requester)) throw new ApiBusinessException("FRIEND_REQUEST_PENDING","A pending request already exists",409);
        UUID id=ids.next(); Instant now=Instant.now();
        jdbc.update("INSERT INTO im_control.friend_requests(id,requester_id,recipient_id,status,note,created_at) VALUES(?,?,?,0,?,?)",
                b(id),b(requester),b(request.recipientId()),blank(request.note()),Timestamp.from(now));
        RequestView result=getRequest(id);
        notify(List.of(requester,request.recipientId()),"FRIEND_REQUEST_CREATED");
        return result;
    }

    List<RequestView> requests(UUID user, String direction) {
        boolean outgoing="outgoing".equalsIgnoreCase(direction);
        String side=outgoing?"r.requester_id":"r.recipient_id";
        return jdbc.query("SELECT r.id,r.requester_id,ru.account,ru.nickname,r.recipient_id,pu.account,pu.nickname,r.status,r.note,r.created_at,r.handled_at FROM im_control.friend_requests r JOIN im_control.users ru ON ru.id=r.requester_id JOIN im_control.users pu ON pu.id=r.recipient_id WHERE "+side+"=? ORDER BY r.created_at DESC LIMIT 200",
                (rs,n)->mapRequest(rs),b(user));
    }

    List<UserSearchView> searchUsers(UUID user, String query, int requestedLimit) {
        String account=query==null?"":query.trim().toLowerCase(Locale.ROOT);
        if(account.length()<3 || account.length()>128)
            throw new ApiBusinessException("INVALID_ACCOUNT_QUERY","IM ID must contain 3 to 128 characters",400);
        int limit=Math.max(1,Math.min(requestedLimit,20));
        String prefix=escapeLike(account)+"%";
        String sql="""
                SELECT u.id,u.account,u.nickname,u.avatar_key,
                  CASE
                    WHEN u.id=? THEN 'SELF'
                    WHEN EXISTS(SELECT 1 FROM im_control.user_blocks b WHERE (b.user_id=? AND b.blocked_user_id=u.id) OR (b.user_id=u.id AND b.blocked_user_id=?)) THEN 'BLOCKED'
                    WHEN EXISTS(SELECT 1 FROM im_control.friendships f WHERE f.user_id=? AND f.friend_id=u.id) THEN 'FRIEND'
                    WHEN EXISTS(SELECT 1 FROM im_control.friend_requests r WHERE r.requester_id=? AND r.recipient_id=u.id AND r.status=0) THEN 'OUTGOING_PENDING'
                    WHEN EXISTS(SELECT 1 FROM im_control.friend_requests r WHERE r.requester_id=u.id AND r.recipient_id=? AND r.status=0) THEN 'INCOMING_PENDING'
                    ELSE 'NONE'
                  END relationship,
                  (SELECT r.id FROM im_control.friend_requests r WHERE r.requester_id=u.id AND r.recipient_id=? AND r.status=0 ORDER BY r.created_at DESC LIMIT 1) pending_request_id,
                  EXISTS(SELECT 1 FROM im_control.friendships f WHERE f.user_id=? AND f.friend_id=u.id) is_friend,
                  EXISTS(SELECT 1 FROM im_control.friend_requests r WHERE r.requester_id=u.id AND r.recipient_id=? AND r.status=0) pending_incoming,
                  EXISTS(SELECT 1 FROM im_control.friend_requests r WHERE r.requester_id=? AND r.recipient_id=u.id AND r.status=0) pending_outgoing,
                  EXISTS(SELECT 1 FROM im_control.user_blocks b WHERE b.user_id=? AND b.blocked_user_id=u.id) blocked_by_me
                FROM im_control.users u
                WHERE u.status=1 AND u.account LIKE ? ESCAPE '='
                ORDER BY CASE WHEN u.account=? THEN 0 ELSE 1 END,u.account
                LIMIT ?
                """;
        byte[] userId=b(user);
        return jdbc.query(sql,(rs,n)->new UserSearchView(
                        UuidCodec.fromBytes(rs.getBytes(1)),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getString(5),rs.getBytes(6)==null?null:UuidCodec.fromBytes(rs.getBytes(6)),
                        rs.getBoolean(7),rs.getBoolean(8),rs.getBoolean(9),rs.getBoolean(10)),
                userId,userId,userId,userId,userId,userId,userId,userId,userId,userId,userId,prefix,account,limit);
    }

    RequestView handle(UUID recipient, UUID requestId, boolean accept) {
        RequestView result=transactions.execute(status -> {
            List<Pair> rows=jdbc.query("SELECT requester_id,recipient_id,status FROM im_control.friend_requests WHERE id=? FOR UPDATE",
                    (rs,n)->new Pair(UuidCodec.fromBytes(rs.getBytes(1)),UuidCodec.fromBytes(rs.getBytes(2)),rs.getInt(3)),b(requestId));
            if(rows.isEmpty()) throw new ApiBusinessException("FRIEND_REQUEST_NOT_FOUND","Friend request does not exist",404);
            Pair pair=rows.getFirst();
            if(!pair.recipient().equals(recipient)) throw new ApiBusinessException("FORBIDDEN","Only the recipient can handle this request",403);
            if(pair.status()!=0) throw new ApiBusinessException("FRIEND_REQUEST_HANDLED","Friend request was already handled",409);
            Instant now=Instant.now();
            jdbc.update("UPDATE im_control.friend_requests SET status=?,handled_at=? WHERE id=?",accept?1:2,Timestamp.from(now),b(requestId));
            if(accept) {
                jdbc.update("INSERT IGNORE INTO im_control.friendships(user_id,friend_id,created_at) VALUES(?,?,?),(?,?,?)",
                        b(pair.requester()),b(pair.recipient()),Timestamp.from(now),b(pair.recipient()),b(pair.requester()),Timestamp.from(now));
            }
            return getRequest(requestId);
        });
        notify(List.of(result.requesterId(),result.recipientId()),"FRIEND_REQUEST_RESOLVED");
        return result;
    }

    RequestView cancel(UUID requester,UUID requestId) {
        RequestView result=transactions.execute(status->{
            List<Pair> rows=jdbc.query("SELECT requester_id,recipient_id,status FROM im_control.friend_requests WHERE id=? FOR UPDATE",
                    (rs,n)->new Pair(UuidCodec.fromBytes(rs.getBytes(1)),UuidCodec.fromBytes(rs.getBytes(2)),rs.getInt(3)),b(requestId));
            if(rows.isEmpty())throw new ApiBusinessException("FRIEND_REQUEST_NOT_FOUND","Friend request does not exist",404);
            Pair pair=rows.getFirst();
            if(!pair.requester().equals(requester))throw new ApiBusinessException("FORBIDDEN","Only the requester can cancel this request",403);
            if(pair.status()!=0)throw new ApiBusinessException("FRIEND_REQUEST_HANDLED","Friend request was already handled",409);
            jdbc.update("UPDATE im_control.friend_requests SET status=3,handled_at=UTC_TIMESTAMP(3) WHERE id=?",b(requestId));
            return getRequest(requestId);
        });
        notify(List.of(result.requesterId(),result.recipientId()),"FRIEND_REQUEST_RESOLVED");
        return result;
    }

    List<FriendView> friends(UUID user) {
        return jdbc.query("SELECT u.id,u.account,u.nickname,u.avatar_key,f.created_at FROM im_control.friendships f JOIN im_control.users u ON u.id=f.friend_id WHERE f.user_id=? AND u.status=1 ORDER BY u.nickname,u.id",
                (rs,n)->new FriendView(UuidCodec.fromBytes(rs.getBytes(1)),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant()),b(user));
    }
    List<BlockView> blocks(UUID user) {
        return jdbc.query("SELECT u.id,u.account,u.nickname,u.avatar_key,b.created_at FROM im_control.user_blocks b JOIN im_control.users u ON u.id=b.blocked_user_id WHERE b.user_id=? ORDER BY b.created_at DESC",
                (rs,n)->new BlockView(UuidCodec.fromBytes(rs.getBytes(1)),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant()),b(user));
    }
    void remove(UUID user, UUID friend) { transactions.executeWithoutResult(s->{jdbc.update("DELETE FROM im_control.friendships WHERE user_id=? AND friend_id=?",b(user),b(friend)); jdbc.update("DELETE FROM im_control.friendships WHERE user_id=? AND friend_id=?",b(friend),b(user));}); notify(List.of(user,friend),"FRIEND_RELATION_CHANGED"); }
    void block(UUID user, UUID blocked) {
        if(user.equals(blocked)) throw new ApiBusinessException("CANNOT_BLOCK_SELF","Cannot block yourself",422);
        requireActiveUser(blocked);
        transactions.executeWithoutResult(s->{
            jdbc.update("INSERT IGNORE INTO im_control.user_blocks(user_id,blocked_user_id,created_at) VALUES(?,?,UTC_TIMESTAMP(3))",b(user),b(blocked));
            jdbc.update("DELETE FROM im_control.friendships WHERE (user_id=? AND friend_id=?) OR (user_id=? AND friend_id=?)",b(user),b(blocked),b(blocked),b(user));
            jdbc.update("UPDATE im_control.friend_requests SET status=3,handled_at=UTC_TIMESTAMP(3) WHERE status=0 AND ((requester_id=? AND recipient_id=?) OR (requester_id=? AND recipient_id=?))",b(user),b(blocked),b(blocked),b(user));
        });
        notify(List.of(user,blocked),"FRIEND_RELATION_CHANGED");
    }
    void unblock(UUID user,UUID blocked){jdbc.update("DELETE FROM im_control.user_blocks WHERE user_id=? AND blocked_user_id=?",b(user),b(blocked));notify(List.of(user,blocked),"FRIEND_RELATION_CHANGED");}

    private RequestView getRequest(UUID id){return jdbc.queryForObject("SELECT r.id,r.requester_id,ru.account,ru.nickname,r.recipient_id,pu.account,pu.nickname,r.status,r.note,r.created_at,r.handled_at FROM im_control.friend_requests r JOIN im_control.users ru ON ru.id=r.requester_id JOIN im_control.users pu ON pu.id=r.recipient_id WHERE r.id=?",(rs,n)->mapRequest(rs),b(id));}
    private RequestView mapRequest(java.sql.ResultSet rs)throws java.sql.SQLException{return new RequestView(UuidCodec.fromBytes(rs.getBytes(1)),UuidCodec.fromBytes(rs.getBytes(2)),rs.getString(3),rs.getString(4),UuidCodec.fromBytes(rs.getBytes(5)),rs.getString(6),rs.getString(7),rs.getInt(8),rs.getString(9),rs.getTimestamp(10).toInstant(),rs.getTimestamp(11)==null?null:rs.getTimestamp(11).toInstant());}
    private void requireActiveUser(UUID id){Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM im_control.users WHERE id=? AND status=1",Integer.class,b(id));if(n==null||n==0)throw new ApiBusinessException("USER_NOT_FOUND","User does not exist",404);}
    private boolean exists(String sql,Object... values){Object[] p=new Object[values.length];for(int i=0;i<values.length;i++)p[i]=values[i] instanceof UUID u?b(u):values[i];Integer n=jdbc.queryForObject(sql,Integer.class,p);return n!=null&&n>0;}
    private String escapeLike(String value){return value.replace("=","==").replace("%","=%").replace("_","=_");}
    private void notify(List<UUID> users,String eventType){
        try { realtime.dispatch(users,"DIRECTORY_EVENT",
                java.util.Map.of("eventType",eventType,"occurredAt",Instant.now().toString())); }
        catch(RuntimeException exception) { log.warn("Friend directory notification {} failed: {}",eventType,exception.getMessage()); }
    }
    private byte[] b(UUID id){return UuidCodec.toBytes(id);} private String blank(String s){return s==null||s.isBlank()?null:s.trim();}
    private record Pair(UUID requester,UUID recipient,int status){}
}
