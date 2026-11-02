# 编译最终版本 echo_server-ubuntu-v1.cpp，输出可执行文件 echo_server
CXX ?= g++
CXXFLAGS ?= -std=c++17 -O2 -Wall -Wextra -pthread

echo_server: echo_server-ubuntu-v1.cpp
	$(CXX) $(CXXFLAGS) -o $@ $<

clean:
	rm -f echo_server

.PHONY: clean
