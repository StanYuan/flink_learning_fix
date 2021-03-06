package com.zhisheng.monitor.pvuv;


import com.zhisheng.common.utils.GsonUtil;
import com.zhisheng.monitor.pvuv.model.UserVisitWebEvent;
import com.zhisheng.monitor.pvuv.utils.UvExampleUtil;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumerBase;
import org.apache.flink.streaming.connectors.redis.RedisSink;
import org.apache.flink.streaming.connectors.redis.common.config.FlinkJedisPoolConfig;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisCommand;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisCommandDescription;
import org.apache.flink.streaming.connectors.redis.common.mapper.RedisMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * @author fanrui
 * @date 2019-10-25 13:17:48
 * @desc 使用 Redis 的 set 数据结构来维护访问过网站各页面的 用户id
 * 对 Redis Connector 不熟悉的请参阅 https://github.com/zhisheng17/flink-learning/blob/master/flink-learning-connectors/flink-learning-connectors-redis/src/main/java/com/zhisheng/connectors/redis/Main.java
 */
public class RedisSetUvExample {
    public static void main(String[] args) throws Exception {

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(TimeUnit.MINUTES.toMillis(1));
        env.setParallelism(2);

        CheckpointConfig checkpointConf = env.getCheckpointConfig();
        checkpointConf.setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
        checkpointConf.enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, UvExampleUtil.broker_list);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "app-uv-stat");

        FlinkKafkaConsumerBase<String> kafkaConsumer = new FlinkKafkaConsumer<>(
                UvExampleUtil.topic, new SimpleStringSchema(), props)
                .setStartFromGroupOffsets();

        FlinkJedisPoolConfig conf = new FlinkJedisPoolConfig
                .Builder().setHost("192.168.30.244").build();

        env.addSource(kafkaConsumer)
                .map(string -> {
                    // 反序列化 JSON
                    UserVisitWebEvent userVisitWebEvent = GsonUtil.fromJson(
                            string, UserVisitWebEvent.class);
                    // 生成 Redis key，格式为 日期_pageId，如: 20191026_0
                    String redisKey = userVisitWebEvent.getDate() + "_"
                            + userVisitWebEvent.getPageId();
                    return Tuple2.of(redisKey, userVisitWebEvent.getUserId());
                })
                .returns(new TypeHint<Tuple2<String, String>>(){})
                .addSink(new RedisSink<>(conf, new RedisSaddSinkMapper()));

        env.execute("Redis Set UV Stat");
    }

    // 数据与 Redis key 的映射关系，并指定将数据 sadd 到 Redis
    public static class RedisSaddSinkMapper
            implements RedisMapper<Tuple2<String, String>> {
        @Override
        public RedisCommandDescription getCommandDescription() {
            //  这里必须是 sadd 操作
            return new RedisCommandDescription(RedisCommand.SADD);
        }

        @Override
        public String getKeyFromData(Tuple2<String, String> data) {
            return data.f0;
        }

        @Override
        public String getValueFromData(Tuple2<String, String> data) {
            return data.f1;
        }
    }
}