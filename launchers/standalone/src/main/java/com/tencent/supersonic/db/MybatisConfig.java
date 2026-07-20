package com.tencent.supersonic.db;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.github.pagehelper.PageInterceptor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import java.util.Properties;

/**
 * Standalone 元数据库 MyBatis-Plus 配置。
 *
 * <p>分页拦截器在数据库侧生成 LIMIT/OFFSET 等方言语句，避免 Forecast 等管理列表先把整表
 * 拉入 JVM；拦截器本身无可变请求状态，可由单例 SqlSessionFactory 并发复用。</p>
 */
@Configuration
@MapperScan(value = "com.tencent.supersonic", annotationClass = Mapper.class)
public class MybatisConfig {

    private static final String MAPPER_LOCATION = "classpath*:mapper/**/*.xml";

    /**
     * 创建支持驼峰映射和数据库侧分页的会话工厂。
     *
     * @param dataSource SuperSonic 元数据库连接池。
     * @return 线程安全的 MyBatis 会话工厂。
     * @throws Exception Mapper XML 解析或工厂初始化失败。
     */
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource,
            PageInterceptor pageInterceptor) throws Exception {
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(configuration);
        bean.setDataSource(dataSource);
        bean.setPlugins(new Interceptor[] {pageInterceptor});

        bean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources(MAPPER_LOCATION));
        return bean.getObject();
    }

    /**
     * 创建运行期自动识别 H2、MySQL 或 PostgreSQL 的分页拦截器。
     *
     * @return 无请求级共享状态的 PageHelper 拦截器。
     */
    @Bean
    public PageInterceptor pageInterceptor() {
        PageInterceptor interceptor = new PageInterceptor();
        Properties properties = new Properties();
        properties.setProperty("autoRuntimeDialect", "true");
        properties.setProperty("closeConn", "true");
        interceptor.setProperties(properties);
        return interceptor;
    }
}
