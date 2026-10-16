package br.com.db1.demooutboxinbox.movimento;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MovimentoRepository extends JpaRepository<MovimentoEntity, Long> {

}
