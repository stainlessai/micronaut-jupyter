#!/usr/bin/env python3
import sys
import time
import jupyter_client
import json
import logging

# Configure logging
logging.basicConfig(
    level=logging.DEBUG,
    format='%(asctime)s.%(msecs)03d [%(name)s] %(levelname)s - %(message)s',
    datefmt='%H:%M:%S'
)

# Set up logger for this script
logger = logging.getLogger('test_kernel')

# Enable debug logging for jupyter_client
logging.getLogger('jupyter_client').setLevel(logging.DEBUG)
logging.getLogger('jupyter_client.manager').setLevel(logging.DEBUG)
logging.getLogger('jupyter_client.client').setLevel(logging.DEBUG)
logging.getLogger('jupyter_client.channels').setLevel(logging.DEBUG)

def test_kernel(connection_file):
    """Test if kernel is available by sending a kernel_info request"""
    logger.debug("Starting kernel test with connection file: %s", connection_file)
    
    try:
        # Read and display connection file contents
        with open(connection_file, 'r') as f:
            conn_data = json.load(f)
        logger.debug("Connection file contents: %s", json.dumps(conn_data, indent=2))
        
        # Create client from existing connection file
        logger.debug("Creating BlockingKernelClient...")
        client = jupyter_client.BlockingKernelClient(connection_file=connection_file)
        
        logger.debug("Loading connection file...")
        client.load_connection_file()
        
        logger.debug("Starting channels...")
        client.start_channels()
        
        logger.debug("Checking if channels are alive...")
        logger.debug("Shell channel alive: %s", client.shell_channel.is_alive())
        logger.debug("IOPub channel alive: %s", client.iopub_channel.is_alive())
        logger.debug("Control channel alive: %s", client.control_channel.is_alive())
        logger.debug("HB channel alive: %s", client.hb_channel.is_alive())
        
        # Wait a moment for connection
        logger.debug("Waiting 2 seconds for connection to stabilize...")
        time.sleep(2)
        
        # Send kernel_info request
        logger.debug("Sending kernel_info request...")
        msg_id = client.kernel_info()
        logger.debug("Sent kernel_info request with msg_id: %s", msg_id)
        
        # Wait for response with timeout
        logger.debug("Waiting for kernel_info_reply...")
        try:
            reply = client.get_shell_msg(timeout=10)
            logger.debug("Received message: %s", json.dumps(reply, indent=2, default=str))
            
            if reply['msg_type'] == 'kernel_info_reply':
                logger.info("SUCCESS: Kernel is available - received kernel_info_reply")
                kernel_info = reply.get('content', {})
                logger.debug("Kernel implementation: %s", kernel_info.get('implementation', 'unknown'))
                logger.debug("Kernel version: %s", kernel_info.get('implementation_version', 'unknown'))
                return 0
            else:
                logger.error("Unexpected reply type: %s", reply['msg_type'])
                return 1
        except Exception as e:
            logger.error("Failed to get kernel response: %s", e)
            logger.debug("Exception type: %s", type(e).__name__)
            
            # Try to get any pending messages
            logger.debug("Checking for any pending messages...")
            try:
                while client.shell_channel.msg_ready():
                    msg = client.get_shell_msg(timeout=0.1)
                    logger.debug("Found pending message: %s", msg.get('msg_type', 'unknown'))
            except:
                pass
            
            return 1
            
    except Exception as e:
        logger.error("Failed to connect to kernel: %s", e)
        logger.debug("Exception type: %s", type(e).__name__)
        import traceback
        logger.debug("Full traceback: %s", traceback.format_exc())
        return 1
    finally:
        logger.debug("Cleaning up client...")
        try:
            client.stop_channels()
            logger.debug("Channels stopped successfully")
        except Exception as e:
            logger.debug("Error stopping channels: %s", e)

if __name__ == "__main__":
    if len(sys.argv) != 2:
        logger.error("Usage: test_kernel.py <connection_file>")
        sys.exit(1)
    
    sys.exit(test_kernel(sys.argv[1]))