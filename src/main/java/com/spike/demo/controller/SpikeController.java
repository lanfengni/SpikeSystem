package com.spike.demo.controller;

import com.spike.demo.global.Constants;
import com.spike.demo.model.Order;
import com.spike.demo.model.Product;
import com.spike.demo.mq.Producer;
import com.spike.demo.service.OrderService;
import com.spike.demo.service.ProductService;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/spike")
public class SpikeController {

    @Autowired
    private OrderService orderService;
    @Autowired
    private ProductService productService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    // 售罄商品列表
    private static ConcurrentHashMap<Long, Boolean> productSoldOutMap = new ConcurrentHashMap<>();
    @Autowired
    private ZooKeeper zooKeeper;
    
    @PostConstruct
    public void initRedis() {
        // 将数据库中所有商品的库存信息同步到 Redis 中
        List<Product> products = productService.getAllProduct();
        for (Product product: products) {
            stringRedisTemplate.opsForValue().set(Constants.PRODUCT_STOCK_PREFIX + product.getId(), product.getStock() + "");
        }
    }

    @PostMapping("/{productId}")
    public String spike(@PathVariable("productId") Long productId) throws KeeperException, InterruptedException {

        if (productSoldOutMap.get(productId) != null){
            return "fail";
        }

        try {
            Long stock = stringRedisTemplate.opsForValue().decrement(Constants.PRODUCT_STOCK_PREFIX + productId);

            if (stock < 0) {
                // 商品销售完后将其加入到售罄列表记录中
                productSoldOutMap.put(productId, true);
                // 保证 Redis 当中的商品库存恒为非负数
                stringRedisTemplate.opsForValue().increment("/" + Constants.PRODUCT_STOCK_PREFIX + productId);

                // zookeeper 中设置售完标记， zookeeper 节点数据格式 product/1 true
                String productPath = "/" + Constants.PRODUCT_STOCK_PREFIX + "/" + productId;
                if (zooKeeper.exists(productPath, true) == null) {
                    zooKeeper.create(productPath, "true".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                }

                // 监听 zookeeper 售完节点
                zooKeeper.exists(productPath, true);

                return "fail";
            }

            // 数据库中减库存
            orderService.spike(productId);

            // 数据库异步减库存
//            producer.spike(productId);

        } catch (Exception e){
            // 数据库减库存失败回滚已售罄列表记录
            if (productSoldOutMap.get(productId) != null) {
                productSoldOutMap.remove(productId);
            }

            // 通过 zookeeper 回滚其他服务器的 JVM 缓存中的商品售完标记
            String path = "/" + Constants.PRODUCT_STOCK_PREFIX + "/" + productId;
            if (zooKeeper.exists(path, true) != null){
                zooKeeper.setData(path, "false".getBytes(), -1);
            }

            // 回滚 Redis 中的库存
            stringRedisTemplate.opsForValue().increment(Constants.PRODUCT_STOCK_PREFIX + productId);
            return "fail";
        }
        return "success";
    }

    public static ConcurrentHashMap<Long, Boolean> getProductSoldOutMap() {
        return productSoldOutMap;
    }
}
