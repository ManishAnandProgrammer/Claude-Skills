package @ROOT_PACKAGE@.room.domain;

import @ROOT_PACKAGE@.common.dropdown.DropdownCandidate;
import @ROOT_PACKAGE@.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "rooms")
@DropdownCandidate(label = "Room", displayField = "name", searchable = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Room extends AuditableEntity {

    @Setter
    @Column(nullable = false)
    private String name;

    @Setter
    @Column(nullable = false)
    private int capacity;

    @Setter
    @Column(nullable = false)
    private String building;
}
