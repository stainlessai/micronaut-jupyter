#!/usr/bin/env python3
import sys
import time
import jupyter_client
import json

def test_kernel(connection_file):
    """Test if kernel is available by sending a kernel_info request"""
    print(f"DEBUG: Starting kernel test with connection file: {connection_file}")
    
    try:
        # Read and display connection file contents
        with open(connection_file, 'r') as f:
            conn_data = json.load(f)
        print(f"DEBUG: Connection file contents: {json.dumps(conn_data, indent=2)}")
        
        # Create client from existing connection file
        print("DEBUG: Creating BlockingKernelClient...")
        client = jupyter_client.BlockingKernelClient(connection_file=connection_file)
        
        print("DEBUG: Loading connection file...")
        client.load_connection_file()
        
        print("DEBUG: Starting channels...")
        client.start_channels()
        
        print("DEBUG: Checking if channels are alive...")
        print(f"DEBUG: Shell channel alive: {client.shell_channel.is_alive()}")
        print(f"DEBUG: IOPub channel alive: {client.iopub_channel.is_alive()}")
        print(f"DEBUG: Control channel alive: {client.control_channel.is_alive()}")
        print(f"DEBUG: HB channel alive: {client.hb_channel.is_alive()}")
        
        # Wait a moment for connection
        print("DEBUG: Waiting 2 seconds for connection to stabilize...")
        time.sleep(2)
        
        # Send kernel_info request
        print("DEBUG: Sending kernel_info request...")
        msg_id = client.kernel_info()
        print(f"DEBUG: Sent kernel_info request with msg_id: {msg_id}")
        
        # Wait for response with timeout
        print("DEBUG: Waiting for kernel_info_reply...")
        try:
            reply = client.get_shell_msg(timeout=10)
            print(f"DEBUG: Received message: {json.dumps(reply, indent=2, default=str)}")
            
            if reply['msg_type'] == 'kernel_info_reply':
                print("SUCCESS: Kernel is available - received kernel_info_reply")
                kernel_info = reply.get('content', {})
                print(f"DEBUG: Kernel implementation: {kernel_info.get('implementation', 'unknown')}")
                print(f"DEBUG: Kernel version: {kernel_info.get('implementation_version', 'unknown')}")
                return 0
            else:
                print(f"ERROR: Unexpected reply type: {reply['msg_type']}")
                return 1
        except Exception as e:
            print(f"ERROR: Failed to get kernel response: {e}")
            print(f"DEBUG: Exception type: {type(e).__name__}")
            
            # Try to get any pending messages
            print("DEBUG: Checking for any pending messages...")
            try:
                while client.shell_channel.msg_ready():
                    msg = client.get_shell_msg(timeout=0.1)
                    print(f"DEBUG: Found pending message: {msg.get('msg_type', 'unknown')}")
            except:
                pass
            
            return 1
            
    except Exception as e:
        print(f"ERROR: Failed to connect to kernel: {e}")
        print(f"DEBUG: Exception type: {type(e).__name__}")
        import traceback
        print(f"DEBUG: Full traceback: {traceback.format_exc()}")
        return 1
    finally:
        print("DEBUG: Cleaning up client...")
        try:
            client.stop_channels()
            print("DEBUG: Channels stopped successfully")
        except Exception as e:
            print(f"DEBUG: Error stopping channels: {e}")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: test_kernel.py <connection_file>")
        sys.exit(1)
    
    sys.exit(test_kernel(sys.argv[1]))