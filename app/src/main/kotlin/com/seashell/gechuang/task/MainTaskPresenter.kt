package com.seashell.gechuang.task

import com.seashell.gechuang.workspace.WorkspaceGeometry

interface MainTaskPresenter {
    fun present(taskId: Int, geometry: WorkspaceGeometry): Result<Unit>
    fun restore(taskId: Int): Result<Unit>
    fun reapply(taskId: Int, geometry: WorkspaceGeometry): Result<Unit>
}
