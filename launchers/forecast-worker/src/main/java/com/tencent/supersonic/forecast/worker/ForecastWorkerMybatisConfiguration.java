package com.tencent.supersonic.forecast.worker;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Worker 元数据库 MyBatis-Plus 配置。
 *
 * <p>
 * 只扫描 Forecast 控制表和现有 {@code DatabaseDOMapper}；不加载 Chat/Headless 业务服务。 SqlSessionFactory 为线程安全单例，每次
 * Mapper 调用由 Spring 管理独立会话。
 * </p>
 */
@Configuration
@MapperScan(
        basePackages = {"com.tencent.supersonic.forecast.server.persistence.mapper",
                        "com.tencent.supersonic.headless.server.persistence.mapper"},
        annotationClass = Mapper.class)
public class ForecastWorkerMybatisConfiguration {

    private static final String MAPPER_LOCATION = "classpath*:mapper/**/*.xml";

    /**
     * 创建支持下划线转驼峰的 SqlSessionFactory。
     *
     * @param dataSource SuperSonic 元数据库连接池。
     * @return MyBatis SqlSessionFactory。
     * @throws Exception Mapper 资源解析或工厂创建失败。
     */
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(configuration);
        bean.setDataSource(dataSource);
        bean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources(MAPPER_LOCATION));
        return bean.getObject();
    }
}
