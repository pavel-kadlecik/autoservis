package cz.palo.autoservis.security.mapper;

import cz.palo.autoservis.model.domain.user.Role;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * MyBatis mapper tabulky {@code security.roles}.
 * SQL je definováno v {@code RoleMapper.xml}.
 */
@Mapper
public interface RoleMapper {

    /**
     * Vrací všechny role definované v systému.
     *
     * @return seznam všech rolí
     */
    List<Role> getAll();
}
