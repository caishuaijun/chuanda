<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span style="font-size: 18px; font-weight: bold;">数据管理</span>
          <el-button type="primary" @click="handleAdd">新增</el-button>
        </div>
      </template>

      <!-- 搜索栏 -->
      <el-row :gutter="16" style="margin-bottom: 16px;">
        <el-col :span="8">
          <el-input
            v-model="searchKeyword"
            placeholder="输入名称搜索..."
            clearable
            @keyup.enter="handleSearch"
            @clear="loadData"
          >
            <template #append>
              <el-button @click="handleSearch">搜索</el-button>
            </template>
          </el-input>
        </el-col>
      </el-row>

      <!-- 数据表格 -->
      <el-table :data="tableData" border stripe v-loading="loading" style="width: 100%">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="名称" width="200" />
        <el-table-column prop="description" label="描述" />
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <el-pagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :total="pagination.total"
        :page-sizes="[5, 10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        style="margin-top: 16px; justify-content: flex-end;"
        @size-change="loadData"
        @current-change="loadData"
      />
    </el-card>

    <!-- 新增/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑' : '新增'"
      width="500px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入名称" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="4"
            placeholder="请输入描述"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchItems, searchItems, createItem, updateItem, deleteItem } from '../api/item.js'

const loading = ref(false)
const submitting = ref(false)
const tableData = ref([])
const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref(null)
const formRef = ref(null)
const searchKeyword = ref('')

const pagination = reactive({
  page: 1,
  size: 10,
  total: 0
})

const form = reactive({
  name: '',
  description: ''
})

const rules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }]
}

function formatTime(timeStr) {
  if (!timeStr) return ''
  return timeStr.replace('T', ' ').substring(0, 19)
}

async function loadData() {
  loading.value = true
  try {
    const res = await fetchItems({
      page: pagination.page - 1,
      size: pagination.size
    })
    tableData.value = res.data.content
    pagination.total = res.data.totalElements
  } catch (err) {
    ElMessage.error('加载数据失败')
    console.error(err)
  } finally {
    loading.value = false
  }
}

async function handleSearch() {
  if (!searchKeyword.value.trim()) {
    loadData()
    return
  }
  loading.value = true
  try {
    const res = await searchItems(searchKeyword.value.trim())
    tableData.value = res.data
    pagination.total = res.data.length
  } catch (err) {
    ElMessage.error('搜索失败')
    console.error(err)
  } finally {
    loading.value = false
  }
}

function handleAdd() {
  isEdit.value = false
  editId.value = null
  form.name = ''
  form.description = ''
  dialogVisible.value = true
}

function handleEdit(row) {
  isEdit.value = true
  editId.value = row.id
  form.name = row.name
  form.description = row.description
  dialogVisible.value = true
}

async function handleSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    if (isEdit.value) {
      await updateItem(editId.value, { name: form.name, description: form.description })
      ElMessage.success('更新成功')
    } else {
      await createItem({ name: form.name, description: form.description })
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    loadData()
  } catch (err) {
    ElMessage.error(isEdit.value ? '更新失败' : '创建失败')
    console.error(err)
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定要删除「${row.name}」吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await deleteItem(row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch (err) {
    if (err !== 'cancel') {
      ElMessage.error('删除失败')
      console.error(err)
    }
  }
}

onMounted(() => {
  loadData()
})
</script>
