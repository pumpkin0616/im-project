package com.example.im.friend;

import com.example.im.common.ApiBusinessException;
import com.example.im.friend.FriendDtos.*;
import com.example.im.message.infrastructure.UuidV7Generator;
import com.example.im.routing.UuidCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
final class FriendService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final UuidV7Generator ids;
    FriendService(JdbcTemplate jdbc, TransactionTemplate transactions, UuidV7Generator ids) {
        this.jdbc=jdbc; this.transactions=transactions; this.ids=ids;
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
        return getRequest(id);
    }

    List<RequestView> requests(UUID user, String direction) {
        boolean outgoing="outgoing".equalsIgnoreCase(direction);
        String side=outgoing?"r.requester_id":"r.recipient_id";
        return jdbc.query("SELECT r.id,r.requester_id,ru.nickname requester_nickname,r.recipient_id,pu.nickname recipient_nickname,r.status,r.note,r.created_at,r.handled_at FROM im_control.friend_requests r JOIN im_control.users ru ON ru.id=r.requester_id JOIN im_control.users pu ON pu.id=r.recipient_id WHERE "+side+"=? ORDER BY r.created_at DESC LIMIT 200",
                (rs,n)->mapRequest(rs),b(user));
    }

    RequestView handle(UUID recipient, UUID requestId, boolean accept) {
        return transactions.execute(status -> {
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
    }

    List<FriendView> friends(UUID user) {
        return jdbc.query("SELECT u.id,u.account,u.nickname,u.avatar_key,f.created_at FROM im_control.friendships f JOIN im_control.users u ON u.id=f.friend_id WHERE f.user_id=? AND u.status=1 ORDER BY u.nickname,u.id",
                (rs,n)->new FriendView(UuidCodec.fromBytes(rs.getBytes(1)),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant()),b(user));
    }
    void remove(UUID user, UUID friend) { transactions.executeWithoutResult(s->{jdbc.update("DELETE FROM im_control.friendships WHERE user_id=? AND friend_id=?",b(user),b(friend)); jdbc.update("DELETE FROM im_control.friendships WHERE user_id=? AND friend_id=?",b(friend),b(user));}); }
    void block(UUID user, UUID blocked) {
        if(user.equals(blocked)) throw new ApiBusinessException("CANNOT_BLOCK_SELF","Cannot block yourself",422);
        requireActiveUser(blocked);
        transactions.executeWithoutResult(s->{
            jdbc.update("INSERT IGNORE INTO im_control.user_blocks(user_id,blocked_user_id,created_at) VALUES(?,?,UTC_TIMESTAMP(3))",b(user),b(blocked));
            jdbc.update("DELETE FROM im_control.friendships WHERE (user_id=? AND friend_id=?) OR (user_id=? AND friend_id=?)",b(user),b(blocked),b(blocked),b(user));
            jdbc.update("UPDATE im_control.friend_requests SET status=3,handled_at=UTC_TIMESTAMP(3) WHERE status=0 AND ((requester_id=? AND recipient_id=?) OR (requester_id=? AND recipient_id=?))",b(user),b(blocked),b(blocked),b(user));
        });
    }
    void unblock(UUID user,UUID blocked){jdbc.update("DELETE FROM im_control.user_blocks WHERE user_id=? AND blocked_user_id=?",b(user),b(blocked));}

    private RequestView getRequest(UUID id){return jdbc.queryForObject("SELECT r.id,r.requester_id,ru.nickname requester_nickname,r.recipient_id,pu.nickname recipient_nickname,r.status,r.note,r.created_at,r.handled_at FROM im_control.friend_requests r JOIN im_control.users ru ON ru.id=r.requester_id JOIN im_control.users pu ON pu.id=r.recipient_id WHERE r.id=?",(rs,n)->mapRequest(rs),b(id));}
    private RequestView mapRequest(java.sql.ResultSet rs)throws java.sql.SQLException{return new RequestView(UuidCodec.fromBytes(rs.getBytes(1)),UuidCodec.fromBytes(rs.getBytes(2)),rs.getString(3),UuidCodec.fromBytes(rs.getBytes(4)),rs.getString(5),rs.getInt(6),rs.getString(7),rs.getTimestamp(8).toInstant(),rs.getTimestamp(9)==null?null:rs.getTimestamp(9).toInstant());}
    private void requireActiveUser(UUID id){Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM im_control.users WHERE id=? AND status=1",Integer.class,b(id));if(n==null||n==0)throw new ApiBusinessException("USER_NOT_FOUND","User does not exist",404);}
    private boolean exists(String sql,Object... values){Object[] p=new Object[values.length];for(int i=0;i<values.length;i++)p[i]=values[i] instanceof UUID u?b(u):values[i];Integer n=jdbc.queryForObject(sql,Integer.class,p);return n!=null&&n>0;}
    private byte[] b(UUID id){return UuidCodec.toBytes(id);} private String blank(String s){return s==null||s.isBlank()?null:s.trim();}
    private record Pair(UUID requester,UUID recipient,int status){}
}
