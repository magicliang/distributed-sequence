const { createApp } = Vue;

createApp({
    data() {
        return {
            loading: false,
            error: '',
            success: '',
            showAdvanced: false,
            serverStatus: {},
            lastResponse: null,
            history: [],
            idRequest: {
                businessType: 'order',
                count: 10,
                timeKey: '',
                includeRouting: false,
                shardDbCount: 4,
                shardTableCount: 8,
                customStepSize: null,
                forceShardType: null
            }
        };
    },
    
    mounted() {
        this.refreshStatus();
        this.loadHistory();
        
        // 定期刷新服务器状态
        setInterval(() => {
            this.refreshStatus();
        }, 30000);
    },
    
    methods: {
        async generateIds() {
            if (!this.idRequest.businessType.trim()) {
                this.showError('请输入业务类型');
                return;
            }
            
            this.loading = true;
            this.error = '';
            
            try {
                const response = await axios.post('/api/id/generate', this.idRequest);
                
                if (response.data.success) {
                    this.lastResponse = response.data.data;
                    this.addToHistory(this.lastResponse);
                    this.showSuccess(`成功生成 ${this.lastResponse.ids.length} 个ID`);
                } else {
                    this.showError(response.data.message || '生成失败');
                }
            } catch (error) {
                console.error('生成ID失败:', error);
                this.showError(error.response?.data?.message || '网络请求失败');
            } finally {
                this.loading = false;
            }
        },
        
        async refreshStatus() {
            try {
                const response = await axios.get('/api/id/status');
                if (response.data.success) {
                    this.serverStatus = response.data.data;
                }
            } catch (error) {
                console.error('获取服务器状态失败:', error);
            }
        },
        
        copyIds() {
            if (!this.lastResponse || !this.lastResponse.ids) {
                return;
            }
            
            const idsText = this.lastResponse.ids.join('\n');
            
            if (navigator.clipboard) {
                navigator.clipboard.writeText(idsText).then(() => {
                    this.showSuccess('ID列表已复制到剪贴板');
                }).catch(() => {
                    this.fallbackCopy(idsText);
                });
            } else {
                this.fallbackCopy(idsText);
            }
        },
        
        fallbackCopy(text) {
            const textArea = document.createElement('textarea');
            textArea.value = text;
            textArea.style.position = 'fixed';
            textArea.style.left = '-999999px';
            textArea.style.top = '-999999px';
            document.body.appendChild(textArea);
            textArea.focus();
            textArea.select();
            
            try {
                document.execCommand('copy');
                this.showSuccess('ID列表已复制到剪贴板');
            } catch (err) {
                this.showError('复制失败，请手动复制');
            }
            
            document.body.removeChild(textArea);
        },
        
        addToHistory(response) {
            const historyItem = {
                ...response,
                timestamp: Date.now()
            };
            
            this.history.unshift(historyItem);
            
            // 只保留最近50条记录
            if (this.history.length > 50) {
                this.history = this.history.slice(0, 50);
            }
            
            this.saveHistory();
        },
        
        loadHistory() {
            try {
                const saved = localStorage.getItem('id_generator_history');
                if (saved) {
                    this.history = JSON.parse(saved);
                }
            } catch (error) {
                console.error('加载历史记录失败:', error);
            }
        },
        
        saveHistory() {
            try {
                localStorage.setItem('id_generator_history', JSON.stringify(this.history));
            } catch (error) {
                console.error('保存历史记录失败:', error);
            }
        },
        
        showError(message) {
            this.error = message;
            setTimeout(() => {
                this.error = '';
            }, 5000);
        },
        
        showSuccess(message) {
            this.success = message;
            setTimeout(() => {
                this.success = '';
            }, 3000);
        },
        
        getShardTypeDesc(shardType) {
            return shardType === 1 ? '奇数分片' : '偶数分片';
        },
        
        formatTime(timestamp) {
            return new Date(timestamp).toLocaleString('zh-CN');
        },
        
        // 快速生成预设
        async quickGenerate(businessType, count = 1) {
            this.idRequest.businessType = businessType;
            this.idRequest.count = count;
            await this.generateIds();
        },
        
        // 批量测试
        async batchTest() {
            const businessTypes = ['order', 'user', 'product', 'payment'];
            const counts = [1, 5, 10, 50];
            
            this.loading = true;
            
            for (const businessType of businessTypes) {
                for (const count of counts) {
                    try {
                        await this.quickGenerate(businessType, count);
                        await new Promise(resolve => setTimeout(resolve, 100)); // 短暂延迟
                    } catch (error) {
                        console.error(`批量测试失败: ${businessType}-${count}`, error);
                    }
                }
            }
            
            this.loading = false;
            this.showSuccess('批量测试完成');
        },
        
        // 性能测试
        async performanceTest() {
            const startTime = Date.now();
            const testCount = 100;
            const promises = [];
            
            this.loading = true;
            
            for (let i = 0; i < testCount; i++) {
                const request = {
                    businessType: `perf_test_${i % 10}`,
                    count: 1,
                    timeKey: new Date().toISOString().slice(0, 10).replace(/-/g, '')
                };
                
                promises.push(axios.post('/api/id/generate', request));
            }
            
            try {
                const responses = await Promise.all(promises);
                const endTime = Date.now();
                const duration = endTime - startTime;
                const successCount = responses.filter(r => r.data.success).length;
                
                this.showSuccess(`性能测试完成: ${successCount}/${testCount} 成功, 耗时: ${duration}ms, 平均: ${(duration/testCount).toFixed(2)}ms/次`);
            } catch (error) {
                this.showError('性能测试失败: ' + error.message);
            } finally {
                this.loading = false;
            }
        },
        
        // 清理历史记录
        clearHistory() {
            if (confirm('确定要清理所有历史记录吗？')) {
                this.history = [];
                this.saveHistory();
                this.showSuccess('历史记录已清理');
            }
        },
        
        // 导出历史记录
        exportHistory() {
            if (this.history.length === 0) {
                this.showError('没有历史记录可导出');
                return;
            }
            
            const data = JSON.stringify(this.history, null, 2);
            const blob = new Blob([data], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            
            const a = document.createElement('a');
            a.href = url;
            a.download = `id_generator_history_${new Date().toISOString().slice(0, 10)}.json`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            
            this.showSuccess('历史记录已导出');
        }
    }
}).mount('#app');