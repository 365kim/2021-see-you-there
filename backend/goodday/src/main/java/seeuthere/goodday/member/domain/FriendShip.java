package seeuthere.goodday.member.domain;

import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.ManyToOne;
import javax.persistence.MapsId;

@Entity
public class FriendShip {

    @EmbeddedId
    private final Key key = new Key();

    @ManyToOne
    @MapsId("ownerId")
    private Member owner;

    @ManyToOne
    @MapsId("friendId")
    private Member friend;

    protected FriendShip() {

    }

    public FriendShip(Member owner, Member friend) {
        this.owner = owner;
        this.friend = friend;
    }

    public Key getKey() {
        return key;
    }

    public Member getOwner() {
        return owner;
    }

    public Member getFriend() {
        return friend;
    }

    @Embeddable
    public static class Key implements Serializable {

        private String ownerId;
        private String friendId;
    }
}
