# publisher_helper.py
import rclpy
from rclpy.node import Node
from std_msgs.msg import Int32
import sys
import time

class DrawerPublisher(Node):
    def __init__(self, value):
        super().__init__('drawer_publisher')
        self.publisher_ = self.create_publisher(Int32, '/cmd_bookshelf_drawer', 10)

        msg = Int32()
        msg.data = value
        self.get_logger().info(f'🔔 퍼블리시: {msg.data}')
        self.publisher_.publish(msg)

        # 퍼블리시 대기 후 종료
        time.sleep(0.3)
        self.destroy_node()
        rclpy.shutdown()

def main():
    rclpy.init()
    value = int(sys.argv[1])
    node = DrawerPublisher(value)

if __name__ == '__main__':
    main()

